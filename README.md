# Rain-World-Interactive-Map
A Rain World interactive map using leaflet and GeoJSON data exported from the game files.
Forked from [Rain-World-Interactive-Map](https://github.com/henpemaz/Rain-World-Interactive-Map) and adjusted to show per-room settings and placed objects too.

[(Link to the map page)](https://rainworldmc.github.io/Rain-World-Interactive-Map/index.html)

This project consists of four parts:
- A c# mod to jump through the game generating screenshots and exporting metadata about rooms and regions and maps. This part is hosted in Henpemaz's [rainworld mods repo](https://github.com/henpemaz/PartModPartMeme) under "MapExporter". Supports modded regions.
- A python script for stitching up the screenshots into a map, producing a tileset/basemap and converting the metadata into geojson features.
- A Database extracted for the RainworldMC project, holding all the data about the rooms, objects, and settings. Used by a python script to generate static json files for the front-end app.
- The front-end app in plain html css and javascript using Leaflet for the map, all static files so it can be hosted in a github site.

The currently tracked things from the game are:
- room placement from the dev-map
- room names
- room connections
- room geometry
- spawns and lineages
- room settings
- placed objects (such as sound triggers, light sources, items, etc)
