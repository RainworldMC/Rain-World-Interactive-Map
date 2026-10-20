"""Restore room origins in existing region JSON from bundled dev-map files.

Run `python backfillRoomOrigins.py` after replacing legacy region JSON, or use
`python backfillRoomOrigins.py --check` to verify the saved origins.
"""
import argparse
import json
from pathlib import Path


def backfill(check=False):
    root = Path(__file__).resolve().parent
    count = 0
    for mode, fixes in [('vanilla', 'MapFixesVanilla'), ('modded', 'MapFixes')]:
        for path in sorted((root / 'docs' / 'tiles' / mode).glob('*/region.json')):
            region = path.parent.name
            source = root / fixes / 'World' / 'Regions' / region / f'map_{region}.txt'
            origins = {}
            for line in source.read_text(encoding='utf-8').splitlines():
                name, separator, values = line.partition(':')
                if not separator or name == 'Connection':
                    continue
                values = values.split(',')
                if len(values) >= 4:
                    # The first pair is the in-game map position. The second
                    # pair is devPos, used by generateGeoJSON.py at 10px/unit.
                    origins[name] = [round(float(v) * 10, 3) for v in values[2:4]]
            data = json.loads(path.read_text(encoding='utf-8'))
            for feature in data['room_features']:
                properties = feature['properties']
                origin = origins[properties['name']]
                if check:
                    if properties.get('room_origin') != origin:
                        raise ValueError(f'{path}: incorrect origin for {properties["name"]}')
                else:
                    properties['room_origin'] = origin
                count += 1
            if not check:
                path.write_text(json.dumps(data), encoding='utf-8')
    print(f'{"Verified" if check else "Restored"} room origins for {count} rooms')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    backfill(parser.parse_args().check)
