import sys,re
# Per-route count of body diff lines in a parity report run with a large --show.
cur=None; counts={}
for line in open(sys.argv[1],encoding='utf-8'):
    if line.startswith('DIFF ') or line.startswith('OK '):
        cur=line.split(': status')[0].split(' ',1)[1].strip() if line.startswith('DIFF') else None
        if cur: counts[cur]=0
    elif cur and line.startswith('   B ') and line[5:6] in '+-' and not line[5:8] in ('---','+++'):
        counts[cur]+=1
for k,v in counts.items(): print(v,k)
print('TOTAL',sum(counts.values()))
