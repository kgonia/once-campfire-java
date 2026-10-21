"""Compares bench.sh runs step by step: python3 compare.py LABEL=RUN_DIR LABEL=RUN_DIR...

The first run is the baseline. For each app, connection count and metric: mean ± sample standard deviation
over the run's reps, and for the other runs the change against the baseline with Welch's t statistic.
|t| above the two-sided 95% critical value for Welch's degrees of freedom marks the change significant.
"""
import csv
import glob
import math
import os
import re
import statistics as st
import sys
from collections import defaultdict

# two-sided 95% critical values of Student's t by degrees of freedom (rounded down to the table's entry)
T95 = {1: 12.71, 2: 4.30, 3: 3.18, 4: 2.78, 5: 2.57, 6: 2.45, 7: 2.36, 8: 2.31, 9: 2.26, 10: 2.23, 12: 2.18, 15: 2.13, 20: 2.09, 30: 2.04}


def t95(df):
    return T95[max(k for k in T95 if k <= max(1, math.floor(df)))]


def load(run):
    steps = defaultdict(lambda: defaultdict(list))
    for props in sorted(glob.glob(os.path.join(run, 'closed', '*.properties'))):
        name = os.path.basename(props)[:-len('.properties')]
        app, _, conns = re.fullmatch(r'(\w+)-(\d+)-c(\d+)', name).groups()
        p = dict(line.strip().split('=', 1) for line in open(props) if '=' in line)
        if p.get('failed') == '1':
            continue
        with open(os.path.join(run, 'closed', name + '.csv')) as f:
            m = next(r for r in csv.DictReader(f) if r['Phase'] == 'main')
        s = steps[(app, int(conns))]
        s['req/s'].append(int(m['http.2xx']) / ((int(m['End']) - int(m['Start'])) / 1000))
        s['CPU µs/req'].append(float(p['cpu_secs']) * 1e6 / float(p['responses']))
        s['p99 ms'].append(int(m['p99.0']) / 1e6)
        s['latency std ms'].append(int(m['StdDev']) / 1e6)
        s['peak RSS MB'].append(float(p['rss_peak_mb']))
        s['app log errors'].append(float(p.get('app_log_errors', 0)))
    return steps


runs = [(a.split('=', 1)[0], load(a.split('=', 1)[1])) for a in sys.argv[1:]]
base_label, base = runs[0]
metrics = ['req/s', 'CPU µs/req', 'p99 ms', 'latency std ms', 'peak RSS MB', 'app log errors']


def fmt(x):
    return f'{x:,.0f}' if abs(x) >= 100 else f'{x:.2f}'


for conns in sorted({c for _, c in base}):
    print(f'\n## {conns} connections\n')
    head = ['app', 'metric'] + [label for label, _ in runs] + [f'{label} vs {base_label}' for label, _ in runs[1:]]
    print('| ' + ' | '.join(head) + ' |')
    print('|' + '---|' * 2 + '---:|' * (len(head) - 2))
    for app in ('rust', 'kotlin', 'quarkus', 'java'):
        if (app, conns) not in base:
            continue
        for k in metrics:
            cells = []
            for _, steps in runs:
                v = steps.get((app, conns), {}).get(k, [])
                cells.append(f'{fmt(st.mean(v))} ± {fmt(st.stdev(v))}' if len(v) > 1 else '-')
            b = base[(app, conns)][k]
            for _, steps in runs[1:]:
                v = steps.get((app, conns), {}).get(k, [])
                if len(v) < 2 or len(b) < 2 or st.mean(b) == 0:
                    cells.append('-')
                    continue
                va, vb = st.variance(v) / len(v), st.variance(b) / len(b)
                se = math.sqrt(va + vb)
                d = st.mean(v) - st.mean(b)
                if se == 0:
                    cells.append(f'{100 * d / st.mean(b):+.1f}%')
                    continue
                t = d / se
                df = (va + vb) ** 2 / ((va ** 2) / (len(v) - 1) + (vb ** 2) / (len(b) - 1))
                mark = 'significant' if abs(t) > t95(df) else 'within noise'
                cells.append(f'{100 * d / st.mean(b):+.1f}% (t={t:.1f}, {mark})')
            print(f'| {app} | {k} | ' + ' | '.join(cells) + ' |')
