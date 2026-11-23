# HOT Semi-Auto Building Detector (JOSM Plugin)

Ein spezialisiertes JOSM-Plugin für die semi-automatische Erfassung von Gebäuden aus Luftbildern, optimiert für Aufgaben im **HOT Tasking Manager**. 

Das Plugin nutzt fortschrittliche Fernerkundungsmethoden (Kantendetektion, Schattenwurf-Analyse, Vegetationsmaskierung) kombiniert mit strengen geodätischen Regeln, um realistische, topologisch korrekte OSM-Gebäude zu generieren.

## ✨ Neue Features (Remote Sensing Edition)

- **Erweiterte Bildverarbeitung (Canny & ExG)**: Robuste Kantendetektion (Canny-Algorithmus) gepaart mit einem Excess Green (ExG) Index, um Baumkronen zuverlässig von Dächern zu unterscheiden.
- **3D-Schattenwurf-Verifikation**: Schätzt den Sonnenazimut und wertet den Schlagschatten aus, um echte 3D-Baukörper von flachen 2D-Strukturen (Parkplätze, Innenhöfe) zu trennen.
- **Nachbarschafts-Konsens**: Analysiert das lokale Siedlungscluster und richtet neue Gebäude automatisch an der dominanten Gebäudeflucht der Umgebung aus (Azimut-Regularisierung).
- **Geodätische Präzision**: Berechnet die Ground Sample Distance (GSD) dynamisch, nutzt die Gaußsche Trapezformel für exakte Flächen und erzwingt rechtwinklige Gebäudeecken (90°) im lokalen metrischen `EastNorth`-System.
- **Nahtlose JOSM-Integration**: Vollständige Unterstützung für den JOSM `UndoRedoHandler` (`Strg+Z`), automatische Selektion der neuen Objekte zur sofortigen Qualitätskontrolle und Konfliktvermeidung mit bereits kartierten OSM-Ways.

## 📦 Installation

### Automatische Installation
1. Lade die kompilierte `.jar`-Datei herunter.
2. Kopiere sie in den JOSM-Plugin-Ordner:
   - **Windows**: `%APPDATA%\JOSM\plugins\`
   - **Linux**: `~/.local/share/JOSM/plugins/` (oder `~/.josm/plugins/`)
   - **macOS**: `~/Library/JOSM/plugins/`
3. Starte JOSM neu und aktiviere das Plugin in den Einstellungen (`F12` -> Plugins).

### Manuelle Kompilierung
```bash
# Baue das Plugin und installiere es automatisch (Linux/macOS)
ant clean install
```
*Tipp: Nutze `ant clean dist`, falls du die `.jar` manuell verschieben möchtest.*

## 🚀 Verwendung (Der HOT-Workflow)

1. **Vorbereiten**: Lade deine Task-BBox im JOSM herunter und aktiviere das empfohlene Luftbild (z. B. Maxar, Bing, Esri).
2. **Zoomen**: Zoome nah an das gewünschte Siedlungscluster heran (empfohlene Bodenauflösung: `< 0.8 m/px`).
3. **Erkennen**: 
   - Klicke auf `Tools > Detect Buildings (HOT Semi-Auto)` 
   - Oder drücke `Strg + Shift + B`
4. **Tuning-Dialog**: Passe die Fernerkundungs-Parameter kurz an die lokalen Gegebenheiten an:
   - *Kontrast-Schwellenwert*: Niedriger (z.B. 0.05) für verblasste Blechdächer, höher (z.B. 0.12) für klare Kontraste.
   - *Schatten-Gewicht*: Wichtig in trockenen Regionen zur Vermeidung von "False Positives" auf dem Boden.
5. **Prüfen**: Die generierten Gebäude werden sofort im JOSM selektiert. Prüfe die Geometrie und drücke bei Fehlern einfach `Strg+Z` (Rückgängig).

## 🧠 Technische Pipeline

1. **Spektrale Vorverarbeitung**: Perzentil-Kontrastspreizung (2%–98%) und Vegetationsmaskierung.
2. **Kanten- & Regionsextraktion**: Non-Maximum Suppression und Hysterese (Canny) generieren scharfe Barrieren. Region-Growing detektiert homogene Dachflächen.
3. **OMBB & Orthogonalisierung**: *Rotating Calipers* finden die flächenminimale orientierte Bounding Box (OMBB). Anschließend werden die Eckpunkte in die aktuelle Kartenprojektion überführt und orthogonalisiert.
4. **Scoring & NMS**: Gebäude werden anhand von Kantenstärke, Schattenwurf und Nachbarschaft bewertet. Überlappungen werden mittels Non-Maximum Suppression aufgelöst.

## 🤝 Mitwirken & Lizenz

Dieses Plugin steht unter der **GPL-3.0 Lizenz**. Pull Requests, Bug Reports und Feedback sind in der OSM-Community jederzeit willkommen!