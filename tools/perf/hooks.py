import sys
from perfetto.trace_processor import TraceProcessor
tp=TraceProcessor(trace=sys.argv[1])
for r in tp.query("""select p.name pn, s.name n, count(*) c, sum(s.dur)/1e6 tot, max(s.dur)/1e6 mx from slice s join thread_track tt on s.track_id=tt.id
   join thread t using(utid) join process p using(upid) where s.name like 'nt:%' group by 1,2 order by tot desc limit 25"""):
    print(f"{r.tot:8.2f} мс ×{r.c:5d} макс {r.mx:6.2f}  {(r.pn or '')[-14:]:14s} {r.n[3:80]}")
tp.close()
