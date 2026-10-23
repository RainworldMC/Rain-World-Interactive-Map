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

        existing_tables = set(
            row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
        )

        regions = {}
        # Keep timeline variants separate; never combine different room versions.
        for row in connection.execute('SELECT * FROM room_settings ORDER BY room_name, timeline'):
            settings = dict(row)
            region = settings.pop('region_name')
            room = settings.pop('room_name')
            timeline = settings.pop('timeline')
            shared = settings.pop('shared_timelines', '')
            settings.pop('modded', None)
            settings.pop('mod_id', None)
            timelines = [t.strip() for t in shared.split(',') if t.strip()] if shared else []

            clean_settings = {k: v for k, v in settings.items() if v is not None and v != ''}
            entry = {
                'settings': clean_settings,
                'effects': [], 'objects': [], 'sounds': [], 'triggers': [],
            }
            if timelines:
                entry['timelines'] = timelines
            regions.setdefault(region.upper(), {}).setdefault(room.upper(), {})[timeline] = entry

        for table, target, index in [
            ('room_setting_effects', 'effects', 'effect_index'),
            ('room_placed_objects', 'objects', 'object_index'),
            ('room_ambient_sounds', 'sounds', 'sound_index'),
            ('room_triggers', 'triggers', 'trigger_index')
        ]:
            if table not in existing_tables:
                continue
            for row in connection.execute(f"SELECT * FROM {table} WHERE owner_kind='room' ORDER BY {index}"):
                row = dict(row)
                entry = regions.setdefault(row['region_name'].upper(), {}).setdefault(
                    row['room_name'].upper(), {}).setdefault(row['timeline'],
                        {'settings': {}, 'effects': [], 'objects': [], 'sounds': [], 'triggers': []})

                if table == 'room_placed_objects':
                    if 'placed_json' in row and row['placed_json']:
                        obj = json.loads(row['placed_json'])
                        obj['object_index'] = row['object_index']
                        entry['objects'].append(obj)
                    else:
                        entry['objects'].append({k: v for k, v in row.items() if v is not None and k not in
                                              ('owner_kind', 'region_name', 'room_name', 'template_name', 'timeline')})
                else:
                    item = {k: v for k, v in row.items() if v is not None and k not in
                            ('owner_kind', 'region_name', 'room_name', 'template_name', 'timeline')}
                    if table == 'room_setting_effects' and 'ripple_json' in item:
                        if item['ripple_json']:
                            try:
                                item['ripple'] = json.loads(item.pop('ripple_json'))
                            except Exception:
                                item.pop('ripple_json', None)
                        else:
                            item.pop('ripple_json', None)
                    entry[target].append(item)

        # Include room headers if present
        if 'room_headers' in existing_tables:
            for row in connection.execute('SELECT room_name, region_name, header_json FROM room_headers'):
                r_name = row['region_name'].upper()
                rm_name = row['room_name'].upper()
                try:
                    header = json.loads(row['header_json'])
                    if r_name in regions and rm_name in regions[r_name]:
                        for variant in regions[r_name][rm_name].values():
                            variant['header'] = header
                except Exception:
                    pass

        # Include creature spawns if present
        if 'room_creature_spawns' in existing_tables:
            for row in connection.execute("SELECT * FROM room_creature_spawns WHERE offscreen=0 ORDER BY spawn_index"):
                row = dict(row)
                r_name = row['region_name'].upper()
                rm_name = row['room_name'].upper()
                timeline = row['timeline']
                if r_name in regions and rm_name in regions[r_name] and timeline in regions[r_name][rm_name]:
                    spawn = {
                        'den': row['den'],
                        'creature_type': row['creature_type'],
                        'amount': row['amount'],
                        'spawn_data': row.get('spawn_data')
                    }
                    entry = regions[r_name][rm_name][timeline]
                    entry.setdefault('spawns', []).append(spawn)

        # Include lineages if present
        if 'room_lineages' in existing_tables:
            stages_by_lineage = {}
            if 'room_lineage_stages' in existing_tables:
                for s_row in connection.execute("SELECT * FROM room_lineage_stages WHERE room_name != '' ORDER BY lineage_index, stage_index"):
                    s_row = dict(s_row)
                    key = (s_row['region_name'].upper(), s_row['room_name'].upper(), s_row['timeline'], s_row['lineage_index'])
                    stages_by_lineage.setdefault(key, []).append({
                        'stage_index': s_row['stage_index'],
                        'creature_type': s_row['creature_type'],
                        'progression_chance': s_row['progression_chance'],
                        'spawn_data': s_row.get('spawn_data')
                    })

            for row in connection.execute("SELECT * FROM room_lineages WHERE offscreen=0 ORDER BY lineage_index"):
                row = dict(row)
                r_name = row['region_name'].upper()
                rm_name = row['room_name'].upper()
                timeline = row['timeline']
                if r_name in regions and rm_name in regions[r_name] and timeline in regions[r_name][rm_name]:
                    key = (r_name, rm_name, timeline, row['lineage_index'])
                    lineage = {
                        'den': row['den'],
                        'night_creature': bool(row['night_creature']),
                        'conflict_index': row['conflict_index'],
                        'stages': stages_by_lineage.get(key, [])
                    }
                    entry = regions[r_name][rm_name][timeline]
                    entry.setdefault('lineages', []).append(lineage)

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