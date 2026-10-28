"""Топ срезов (atrace) на главном потоке и RenderThread процесса за трассу: где уходит время кадров."""
import sys
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); proc = sys.argv[2]
print("== кадры: doFrame / DrawFrame (мс): медиана, 95%, макс, всего")
for name in ('Choreographer#doFrame%', 'DrawFrame%', 'drawFrame%'):
    ds = sorted(r.d / 1e6 for r in tp.query(f"""select s.dur d from slice s join thread_track tt on s.track_id = tt.id
        join thread t using(utid) join process p using(upid) where p.name like '{proc}%' and s.name like '{name}' """))
    if ds:
        print(f"  {name:24s} {ds[len(ds)//2]:6.2f} {ds[int(len(ds)*.95)]:6.2f} {ds[-1]:6.2f}  n={len(ds)}")
print("== тяжёлые срезы (сумма мс, число, поток)")
for r in tp.query(f"""select s.name n, t.name th, count(*) c, sum(s.dur)/1e6 tot, max(s.dur)/1e6 mx
    from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid) join process p using(upid)
    where p.name like '{proc}%' and s.depth > 0 group by s.name, t.name order by tot desc limit 22"""):
    print(f"  {r.tot:8.1f} мс  ×{r.c:4d}  макс {r.mx:6.2f}  [{(r.th or '')[:14]}] {r.n[:70]}")
tp.close()
