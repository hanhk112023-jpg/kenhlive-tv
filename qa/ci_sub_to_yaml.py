#!/usr/bin/env python3
"""Chuyển subscription YAML (sub_full.yaml) → config mihomo tối giản POOL VN/SG."""
import yaml

cfg = yaml.safe_load(open('/tmp/sub_full.yaml'))
names = cfg.get('proxies', [])
order = [p for p in names if 'VN' in p.get('name', '').upper()] + \
        [p for p in names if 'SG' in p.get('name', '').upper()]
keep = order[:10]
groups = [{'name': 'POOL', 'type': 'url-test', 'proxies': [p['name'] for p in keep],
           'url': 'http://www.gstatic.com/generate_204', 'interval': 120, 'tolerance': 50}]
out = {'mixed-port': 7891, 'allow-lan': True, 'mode': 'rule', 'log-level': 'warning',
       'proxies': keep, 'proxy-groups': groups, 'rules': ['MATCH,POOL']}
yaml.safe_dump(out, open('/tmp/mihomo_ci.yaml', 'w'))
print(f'sub→mihomo OK: {len(keep)} proxies')
