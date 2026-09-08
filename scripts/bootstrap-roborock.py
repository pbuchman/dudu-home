#!/usr/bin/env python3
"""Computer-only email login + read-only routine discovery. Never executes a routine."""
import argparse
import asyncio
import getpass
import json
import logging
import os
from pathlib import Path
import sys

from importlib.util import module_from_spec, spec_from_file_location
spec = spec_from_file_location('private_installer', Path(__file__).with_name('configure-device.py'))
installer = module_from_spec(spec)
spec.loader.exec_module(installer)


def minimal_bundle(user, scene, mop=None):
    r = user.rriot
    value = dict(schema_version=1, api_base_url=r.r.a, routine_id=scene.id,
                 routine_name=scene.name, auth=dict(u=r.u, s=r.s, h=r.h))
    if mop is not None:
        if mop.name != 'Full Mop': raise ValueError('Unexpected manual routine')
        value['full_mop_routine_id'] = mop.id
    return installer.validate(dict(schema_version=2), value, False)['roborock']


async def collect():
    import aiohttp
    from roborock.web_api import RoborockApiClient
    email = getpass.getpass('Roborock account email (hidden): ').strip()
    async with aiohttp.ClientSession() as session:
        api = RoborockApiClient(email, session=session)
        await api.request_code()
        code = getpass.getpass('Email verification code (hidden): ').strip()
        user = await api.code_login(code)
        home = await api.get_home_data(user)
        matches = []
        for device in home.get_all_devices():
            scenes = await api.get_scenes(user, device.duid)
            matches.extend((scene, scenes) for scene in scenes if scene.name == 'Full Cleaning')
        if len(matches) != 1:
            raise ValueError('Expected exactly one Full Cleaning routine; resolve ambiguity in the Roborock app')
        cleaning, scenes = matches[0]
        mops = [scene for scene in scenes if scene.name == 'Full Mop']
        if len(mops) > 1: raise ValueError('Ambiguous Full Mop routine')
        return minimal_bundle(user, cleaning, mops[0] if mops else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=installer.private_path)
    args = parser.parse_args()
    os.umask(0o077)
    logging.disable(logging.CRITICAL)
    if args.output.exists(): raise ValueError('Output already exists; choose a new private filename to preserve the old bundle')
    args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if args.output.parent.stat().st_mode & 0o077: raise ValueError('Output directory must be owner-only (chmod 700)')
    value = asyncio.run(collect())
    with args.output.open('x', encoding='utf-8') as target:
        json.dump(value, target, indent=2)
        target.flush(); os.fsync(target.fileno())
    print('Minimal routine credentials saved privately. No routine was executed.')


if __name__ == '__main__':
    try: main()
    except Exception as error:
        # API exceptions can embed headers/account payloads. Never print their contents.
        print('Bootstrap failed: ' + type(error).__name__ + '. Existing credentials preserved.', file=sys.stderr)
        raise SystemExit(1)
