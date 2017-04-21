#!/usr/bin/env python3
raw = input('version: ')
version = [int(x) for x in raw.split('.')]

if version[1] >= 100:
    raise SystemError("minor version must be less than 100")
if version[2] >= 100:
    raise SystemError("micro version must be less than 100")

with open('build.gradle') as f:
    lines = f.readlines()

with open('build.gradle', 'w') as f:
    for line in lines:
        if 'versionCode' in line:
            t = line.split('versionCode')
            code = version[0] * 10000 + version[1] * 100 + version[2]
            line = '{}versionCode {}\n'.format(t[0], code)
        elif 'versionName' in line:
            t = line.split('versionName')
            line = '{}versionName "{}.{}.{}"\n'.format(t[0], *version)
        f.write(line)
