# WorldBorder Expander

Eine serverseitige Fabric-Mod entwickelt für Minecraft 1.21.11, die die Weltbarriere automatisch zu einer konfigurierbaren Uhrzeit erweitert oder verkleinert.
<br/><br/>
## Features

- **Automatische Erweiterung**: Die World Border wird täglich zu einer konfigurierbaren Uhrzeit angepasst
- **Flexible Konfiguration**: Größe und Uhrzeit lassen sich flexibel über Befehle oder die Konfigurationsdatei anpassen
- **MOTD-Integration**: Zeigt die aktuelle Border-Größe und nächste Erweiterung in der Server-Liste
- **Serverside-only**: Keine Client-Mod erforderlich
  
<br/><br/>
## Installation

1. Installiere Fabric Loader 0.18.1+ für Minecraft 1.21.11 (hierfür getestet) 
2. Installiere Fabric API 0.110.5+
3. Platziere die Mod-Datei im `mods/` Ordner deines Servers
4. Starte den Server
   
<br/><br/>
## Befehle

### Administrator-Befehle (OP-Level erforderlich)

#### Erweiterungsgröße konfigurieren
```
/expandborder configure expansion <amount>
```

Setzt die Anzahl der Blöcke, um die die Border erweitert wird.

- `<amount>`: Beliebige Ganzzahl (positiv für Erweiterung, negativ für Verkleinerung)
- Beispiel: `/expandborder configure expansion 100`
- Beispiel: `/expandborder configure expansion -50`

#### Uhrzeit konfigurieren
```
/expandborder configure time <hour> [minute]
```

Setzt die tägliche Uhrzeit für die automatische Erweiterung.

- `<hour>`: Stunde im 24-Stunden-Format (0-23)
- `[minute]`: Optional, Minute (0-59), Standard: 0
- Beispiel: `/expandborder configure time 20` (20:00 Uhr)
- Beispiel: `/expandborder configure time 18 30` (18:30 Uhr)

#### Manuelle Erweiterung
```
/expandborder now
```

Führt die Border-Erweiterung sofort aus, unabhängig von der konfigurierten Uhrzeit.

#### Automatik aktivieren/deaktivieren
```
/expandborder toggle
```

Schaltet die automatische tägliche Erweiterung ein oder aus.

- Bei Aktivierung wird die nächste Erweiterung neu geplant
- Bei Deaktivierung wird der Timer gestoppt
<br/><br/>
### Öffentliche Befehle (für alle Spieler)

#### Informationen anzeigen
```
/expandborder info
```

Zeigt die aktuelle Konfiguration an:

- Status der automatischen Erweiterung
- Konfigurierte Uhrzeit
- Erweiterungsgröße
- Aktuelle World Border Größe

<br/><br/>
## Konfiguration

Die Konfigurationsdatei wird automatisch unter `config/worldborderexpander.json` erstellt:
```json
{
  "autoExpansionEnabled": false,
  "expansionAmount": 100,
  "targetHour": 0,
  "targetMinute": 0
}
```

### Parameter

- `autoExpansionEnabled`: Aktiviert/deaktiviert die automatische Erweiterung
- `expansionAmount`: Anzahl der Blöcke für die Erweiterung (kann negativ sein)
- `targetHour`: Stunde der täglichen Erweiterung (0-23)
- `targetMinute`: Minute der täglichen Erweiterung (0-59)

<br/><br/>
## MOTD-Integration

Die Mod erweitert die Server-Beschreibung (MOTD) automatisch um folgende Informationen:

- Aktuelle World Border Größe
- Nächste Erweiterungszeit (falls aktiviert)
