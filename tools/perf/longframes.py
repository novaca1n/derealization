"""Долгие кадры процесса (doFrame > порога) и что внутри них — дерево срезов до глубины 3."""
import sys
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); proc = sys.argv[2]; thr = float(sys.argv[3]) if len(sys.argv) > 3 else 8.3
t0 = next(iter(tp.query("select min(ts) m from slice"))).m
for f in tp.query(f"""select s.id, s.ts, s.dur, s.name, s.depth dp from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid)
      join process p using(upid) where p.name like '{proc}%' and s.name like 'Choreographer#doFrame%' and s.dur > {thr}*1e6 order by s.ts"""):
    print(f"{(f.ts - t0) / 1e9:6.2f} с  кадр {f.dur / 1e6:5.1f} мс")
    for c in tp.query(f"""select name, dur, depth from descendant_slice({f.id}) where depth <= {f.dp} + 3 and dur > 0.8e6 order by ts"""):
        print(f"           {"  " * (c.depth - f.dp - 1)}{c.dur / 1e6:5.1f}  {c.name[:80]}")
tp.close()
