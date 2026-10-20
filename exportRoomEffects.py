"""Export SQLite room effects as static, per-region JSON for the map.

Run: python exportRoomEffects.py [database] [output-directory]
No third-party dependencies or database writes are required.
"""
import argparse
import json
import sqlite3
from pathlib import Path


def export(database, output):
    output.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True) as connection:
        connection.row_factory = sqlite3.Row
        regions = {}
        # Keep timeline variants separate; never combine different room versions.
        for row in connection.execute('SELECT * FROM room_settings ORDER BY room_name, timeline'):
            settings = dict(row)
            region = settings.pop('region_name')
            room = settings.pop('room_name')
            timeline = settings.pop('timeline')
            regions.setdefault(region.upper(), {}).setdefault(room.upper(), {})[timeline] = {
                'settings': {k: v for k, v in settings.items() if v is not None},
                'effects': [], 'objects': [], 'sounds': [], 'triggers': [],
            }
        for table, target, index in [('room_setting_effects', 'effects', 'effect_index'),
                                      ('room_placed_objects', 'objects', 'object_index'),
                                      ('room_ambient_sounds', 'sounds', 'sound_index'),
                                      ('room_triggers', 'triggers', 'trigger_index')]:
            for row in connection.execute(f"SELECT * FROM {table} WHERE owner_kind='room' ORDER BY {index}"):
                row = dict(row)
                # Some gate rows have effects/objects for both region owners but
                # only one room_settings row. Preserve each side's own data.
                entry = regions.setdefault(row['region_name'].upper(), {}).setdefault(
                    row['room_name'].upper(), {}).setdefault(row['timeline'],
                        {'settings': {}, 'effects': [], 'objects': [], 'sounds': [], 'triggers': []})
                # Room rows already contain inherited effects (see RoomSettingsStore).
                entry[target].append({k: v for k, v in row.items() if v is not None and k not in
                                      ('owner_kind', 'region_name', 'room_name', 'template_name', 'timeline')})
        for region, rooms in sorted(regions.items()):
            (output / (region + '.json')).write_text(json.dumps({'rooms': rooms}, separators=(',', ':'),
                                                               ensure_ascii=True, allow_nan=False), encoding='utf-8')
        # Gate rooms can be owned by either adjoining region (including renamed
        # regions in newer game data), so resolve by room name when necessary.
        index = {name: region for region, rooms in regions.items() for name in rooms}
        # Prefer the owner with room settings for fallback name lookups.
        index.update({name: region for region, rooms in regions.items() for name, variants in rooms.items()
                      if any(v['settings'] for v in variants.values())})
        (output / 'index.json').write_text(json.dumps(index, separators=(',', ':')), encoding='utf-8')
        print(f'Exported {sum(len(r) for r in regions.values())} rooms in {len(regions)} regions to {output}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', nargs='?', type=Path, default=Path('rainworld-rooms.db'))
    parser.add_argument('output', nargs='?', type=Path, default=Path('docs/room-effects'))
    args = parser.parse_args()
    export(args.database, args.output)
