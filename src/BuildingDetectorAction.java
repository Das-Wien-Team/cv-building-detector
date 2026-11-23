package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.DetectionConfig;
import org.openstreetmap.josm.tools.Shortcut;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * JOSM-Action für die semi-automatische Gebäudedetektion in HOT-Tasking-Manager-Aufgaben.
 *
 * Verbesserungen:
 * - Rendert den reinen ImageryLayer offscreen (ohne störende OSM-Vektor-Overlays oder Mauszeiger)
 * - Unterstützt alle Luftbild-Quellen (Bing, Esri, Maxar, OpenAerialMap, Custom TMS)
 * - Bietet einen kompakten Fernerkundungs-Parameterdialog (Kanten-Schwellenwert, Schattenwurf-Gewicht,
 *   Mindest-/Maximalfläche in m², Nachbarschafts-Fluchtausrichtung)
 * - Vollständig Thread-sicher zwischen Swing-EDT und Worker-Thread
 */
public class BuildingDetectorAction extends JosmAction {

    private static final long serialVersionUID = 1L;

    private final ImageProcessor imageProcessor;
    private final BuildingCreator buildingCreator;

    // Persistente Parameter innerhalb der Sitzung für schnelles Arbeiten im HOT Tasking Manager
    private double lastContrastThreshold = 0.08;
    private double lastShadowWeight = 0.35;
    private double lastMinAreaM2 = 12.0;
    private double lastMaxAreaM2 = 2000.0;
    private boolean lastAlignNeighbors = true;
    private boolean lastShowDebug = false;

    public BuildingDetectorAction() {
        super(
            tr("Detect Buildings (HOT Semi-Auto)"),
            "building_detector",
            tr("Semi-automatische Gebäudedetektion aus Luftbildern (Kanten, Schattenwurf, Nachbarschaft)"),
            Shortcut.registerShortcut(
                "tools:buildingdetector",
                tr("Tool: {0}", tr("Building Detector")),
                KeyEvent.VK_B,
                Shortcut.CTRL_SHIFT
            ),
            true
        );

        this.imageProcessor = new ImageProcessor();
        this.buildingCreator = new BuildingCreator();
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OsmDataLayer dataLayer = MainApplication.getLayerManager().getEditLayer();
        if (dataLayer == null) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Bitte laden Sie zuerst OSM-Daten oder erstellen Sie eine neue Datenebene."),
                tr("Keine Datenebene"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        ImageryLayer imageryLayer = findImageryLayer();
        if (imageryLayer == null) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Keine sichtbare Luftbild-Ebene gefunden. Bitte aktivieren Sie Bing, Esri, Maxar oder OAM."),
                tr("Keine Luftbilder"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        double currentGsd = buildingCreator.computeCurrentMetersPerPixel();
        if (currentGsd > 1.2) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Bitte zoomen Sie näher heran (aktuelle Bodenauflösung: {0} m/px). Für Gebäudedetektion wird < 0.8 m/px empfohlen.",
                   String.format("%.2f", currentGsd)),
                tr("Zoom-Level zu niedrig"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        // Kompaktes Tuning-Panel auf dem EDT anzeigen
        DetectionConfig config = promptDetectionConfig(currentGsd, imageryLayer.getName());
        if (config == null) {
            return; // Vom Benutzer abgebrochen
        }

        performBuildingDetection(imageryLayer, dataLayer, config);
    }

    /**
     * Zeigt einen übersichtlichen Parameter-Dialog für Kanten-, Schatten- und Nachbarschaftsparameter.
     */
    private DetectionConfig promptDetectionConfig(double currentGsd, String imageryName) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 6));

        JSpinner contrastSpinner = new JSpinner(new SpinnerNumberModel(lastContrastThreshold, 0.02, 0.30, 0.01));
        JSpinner shadowSpinner = new JSpinner(new SpinnerNumberModel(lastShadowWeight, 0.00, 0.80, 0.05));
        JSpinner minAreaSpinner = new JSpinner(new SpinnerNumberModel(lastMinAreaM2, 4.0, 500.0, 2.0));
        JSpinner maxAreaSpinner = new JSpinner(new SpinnerNumberModel(lastMaxAreaM2, 50.0, 10000.0, 50.0));
        JCheckBox alignCheck = new JCheckBox(tr("An Nachbarschaftsflucht ausrichten"), lastAlignNeighbors);
        JCheckBox debugCheck = new JCheckBox(tr("Fernerkundungs-Maske anzeigen (Debug)"), lastShowDebug);

        panel.add(new JLabel(tr("Aktives Luftbild:")));
        panel.add(new JLabel(imageryName + String.format(" (GSD: %.2f m/px)", currentGsd)));
        panel.add(new JLabel(tr("Canny-Kanten-Schwellenwert (0.04 - 0.15):")));
        panel.add(contrastSpinner);
        panel.add(new JLabel(tr("3D-Schattenwurf-Gewichtung (0.0 - 0.8):")));
        panel.add(shadowSpinner);
        panel.add(new JLabel(tr("Minimale Gebäudefläche (m²):")));
        panel.add(minAreaSpinner);
        panel.add(new JLabel(tr("Maximale Gebäudefläche (m²):")));
        panel.add(maxAreaSpinner);
        panel.add(alignCheck);
        panel.add(debugCheck);

        int res = JOptionPane.showConfirmDialog(
            MainApplication.getMainFrame(),
            panel,
            tr("HOT Building Detector – Fernerkundungs-Parameter"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );

        if (res != JOptionPane.OK_OPTION) {
            return null;
        }

        lastContrastThreshold = ((Number) contrastSpinner.getValue()).doubleValue();
        lastShadowWeight = ((Number) shadowSpinner.getValue()).doubleValue();
        lastMinAreaM2 = ((Number) minAreaSpinner.getValue()).doubleValue();
        lastMaxAreaM2 = ((Number) maxAreaSpinner.getValue()).doubleValue();
        lastAlignNeighbors = alignCheck.isSelected();
        lastShowDebug = debugCheck.isSelected();

        DetectionConfig config = new DetectionConfig();
        config.contrastThreshold = lastContrastThreshold;
        config.shadowWeight = lastShadowWeight;
        config.minBuildingAreaM2 = lastMinAreaM2;
        config.maxBuildingAreaM2 = lastMaxAreaM2;
        config.metersPerPixel = currentGsd;
        config.alignWithNeighbors = lastAlignNeighbors;
        config.showDebugWindows = lastShowDebug;
        return config;
    }

    /**
     * Extrahiert das reine Luftbild auf dem EDT und führt die Detektion asynchron aus.
     */
    private void performBuildingDetection(ImageryLayer imageryLayer, OsmDataLayer dataLayer, DetectionConfig config) {
        // 1. Sauberes Offscreen-Rendering des ImageryLayers ohne OSM-Daten-Overlay
        BufferedImage aerialImage = extractPureImagery(imageryLayer);
        if (aerialImage == null) {
            showError(tr("Konnte kein Luftbild extrahieren. Stellen Sie sicher, dass die Luftbild-Ebene sichtbar ist."));
            return;
        }

        // 2. Hintergrund-Thread für rechenintensive Canny-, Schatten- & Nachbarschaftsanalyse
        Thread detectionThread = new Thread(() -> {
            try {
                List<BuildingContour> detectedBuildings = imageProcessor.detectBuildings(aerialImage, config);

                // 3. Erstellung der OSM-Objekte und UndoRedo-Registrierung zurück auf dem Swing-EDT
                SwingUtilities.invokeLater(() -> {
                    int createdBuildings = buildingCreator.createOSMBuildings(detectedBuildings, dataLayer.getDataSet());
                    MainApplication.getMap().repaint();

                    JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        tr("{0} Gebäude erkannt, orthogonalisiert und ausgewählt.\n"
                         + "(Tipp: Mit Strg+Z rückgängig machen oder Auswahl direkt prüfen)", createdBuildings),
                        tr("Erkennung abgeschlossen"),
                        JOptionPane.INFORMATION_MESSAGE
                    );
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                showError(tr("Fehler bei der Bildverarbeitung: {0}", ex.getMessage()));
            }
        }, "JOSM-BuildingDetector-Worker");

        detectionThread.start();
    }

    /**
     * Rendert ausschließlich den ImageryLayer in ein Offscreen-BufferedImage, sodass
     * bestehende OSM-Linien, Nodes, Auswahl-Highlights und UI-Elemente das Kantenbild nicht verfälschen.
     */
    private BufferedImage extractPureImagery(ImageryLayer imageryLayer) {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return null;
        }
        MapView mapView = MainApplication.getMap().mapView;
        int w = mapView.getWidth();
        int h = mapView.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }

        try {
            AtomicReference<BufferedImage> ref = new AtomicReference<>();
            Runnable renderTask = () -> {
                BufferedImage offscreen = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = offscreen.createGraphics();
                try {
                    Bounds bounds = mapView.getRealBounds();
                    imageryLayer.paint(g2d, mapView, bounds);
                } finally {
                    g2d.dispose();
                }
                ref.set(offscreen);
            };

            if (SwingUtilities.isEventDispatchThread()) {
                renderTask.run();
            } else {
                SwingUtilities.invokeAndWait(renderTask);
            }

            BufferedImage rendered = ref.get();
            if (rendered != null) {
                return rendered;
            }
        } catch (Exception ex) {
            // Fallback auf Robot-Screenshot falls das direkte Layer-Painting fehlschlägt
            ex.printStackTrace();
        }

        try {
            Point screenPos = mapView.getLocationOnScreen();
            Rectangle rect = new Rectangle(screenPos.x, screenPos.y, w, h);
            return new Robot().createScreenCapture(rect);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Findet die oberste sichtbare Luftbild-Ebene (unterstützt Bing, Esri, Maxar, OAM, Custom TMS etc.).
     */
    private ImageryLayer findImageryLayer() {
        List<Layer> layers = MainApplication.getLayerManager().getLayers();
        for (Layer layer : layers) {
            if (layer instanceof ImageryLayer && layer.isVisible()) {
                return (ImageryLayer) layer;
            }
        }
        return null;
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                message,
                tr("Fehler"),
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}