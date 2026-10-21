"""Spread of a bench.sh run's all-out steps: within each step (second by second) and between reps.

    python3 stats.py RUN_DIR

Per step, from Hyperfoil's measured ("main") phase and bench.sh's .properties:
  req/s            2xx responses / the phase's duration (as Report.java)
  req/s per second the 1 s intervals of main.fetch.*.series.csv: their mean, standard deviation, CV
  latency          Hyperfoil's mean and standard deviation over all responses, p50, p99
  CPU µs/req       the app's CPU over both phases / their responses (as Report.java)
  RSS, peak RSS    the app's, after the step
  busy             app CPUs and load driver CPUs, average busy cores over the step
Between reps: mean, sample standard deviation, CV, median, min and max of each step metric.
"""
import csv
import glob
import os
import re
import statistics as st
import sys
from collections import defaultdict

run = sys.argv[1]


def main_row(path):
    with open(path) as f:
        for row in csv.DictReader(f):
            if row['Phase'] == 'main':
                return row


def series(hf_dir):
    """Requests/s of each full 1 s interval of the measured phase."""
    files = glob.glob(os.path.join(hf_dir, 'run', '*', 'stats', 'main.fetch.*.series.csv'))
    files = [f for f in files if '.agent.' not in f]
    out = []
    with open(files[0]) as f:
        for row in csv.DictReader(f):
            ms = int(row['End']) - int(row['Start'])
            if ms >= 990:
                out.append(int(row['http.2xx']) * 1000 / ms)
    return out


steps = defaultdict(lambda: defaultdict(list))  # (app, conns) -> metric -> [value per rep]
for props in sorted(glob.glob(os.path.join(run, 'closed', '*.properties'))):
    name = os.path.basename(props)[:-len('.properties')]
    app, rep, conns = re.fullmatch(r'(\w+)-(\d+)-c(\d+)', name).groups()
    p = dict(line.strip().split('=', 1) for line in open(props) if '=' in line)
    if p.get('failed') == '1':
        print(f'{name}: failed', file=sys.stderr)
        continue
    m = main_row(os.path.join(run, 'closed', name + '.csv'))
    secs = (int(m['End']) - int(m['Start'])) / 1000
    per_sec = series(os.path.join(run, 'closed', name + '-hyperfoil'))
    s = steps[(app, int(conns))]
    s['req/s'].append(int(m['http.2xx']) / secs)
    s['req/s per-second mean'].append(st.mean(per_sec))
    s['req/s per-second std'].append(st.stdev(per_sec))
    s['req/s per-second CV %'].append(100 * st.stdev(per_sec) / st.mean(per_sec))
    s['latency mean ms'].append(int(m['Mean']) / 1e6)
    s['latency std ms'].append(int(m['StdDev']) / 1e6)
    s['latency p50 ms'].append(int(m['p50.0']) / 1e6)
    s['latency p99 ms'].append(int(m['p99.0']) / 1e6)
    s['CPU µs/req'].append(float(p['cpu_secs']) * 1e6 / float(p['responses']))
    s['RSS MB'].append(float(p['rss_mb']))
    s['peak RSS MB'].append(float(p['rss_peak_mb']))
    s['app CPUs busy'].append(float(p['app_cpus_busy']))
    s['driver CPUs busy'].append(float(p['loadgen_busy']))
    s['errors'].append(sum(int(m[k]) for k in ('ConnectionErrors', 'RequestTimeouts', 'InternalErrors', 'Invalid', 'http.4xx', 'http.5xx')))
    s['listen overflows'].append(int(p.get('listen_overflows', 0)))

metrics = ['req/s', 'req/s per-second std', 'req/s per-second CV %', 'latency mean ms', 'latency std ms',
           'latency p50 ms', 'latency p99 ms', 'CPU µs/req', 'RSS MB', 'peak RSS MB', 'app CPUs busy',
           'driver CPUs busy', 'errors', 'listen overflows']


def fmt(x):
    return f'{x:,.0f}' if abs(x) >= 100 else f'{x:.2f}'


for conns in sorted({c for _, c in steps}):
    print(f'\n## {conns} connections\n')
    print('| app | metric | reps | mean | std between reps | CV | median | min | max | values |')
    print('|---|---|---:|---:|---:|---:|---:|---:|---:|---|')
    for app in ('rust', 'kotlin', 'quarkus', 'java'):
        s = steps.get((app, conns))
        if not s:
            continue
        for k in metrics:
            v = s[k]
            mean = st.mean(v)
            sd = st.stdev(v) if len(v) > 1 else float('nan')
            cv = f'{100 * sd / mean:.1f}%' if mean else '-'
            print(f'| {app} | {k} | {len(v)} | {fmt(mean)} | {fmt(sd)} | {cv} | {fmt(st.median(v))} | {fmt(min(v))} | '
                  f'{fmt(max(v))} | {" ".join(fmt(x) for x in v)} |')
