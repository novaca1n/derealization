"""Начало шторки: для каждого касания строки состояния — когда прошёл relayoutWindow окна шторки (от касания),
самый долгий кадр SystemUI в первые 200 мс после касания и задержка первого кадра шторки."""
import sys
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1])
q = lambda s: list(tp.query(s))
SUI = "p.name like 'com.android.systemui%'"
J = "from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)"
downs = [r.ts for r in q(f"select s.ts {J} where {SUI} and s.name like 'dispatchInputEvent MotionEvent ACTION_DOWN%' order by s.ts")]
for d in downs:
    rel = q(f"select s.ts, s.dur {J} where {SUI} and s.name like 'VRI[NotificationShade]-relayoutWindow%' and s.ts between {d} and {d}+400e6 order by s.ts limit 1")
    mv = q(f"select s.ts {J} where {SUI} and s.name like 'dispatchInputEvent MotionEvent ACTION_MOVE%' and s.ts > {d} order by s.ts limit 1")
    mx = q(f"select max(s.dur) m {J} where {SUI} and s.name like 'Choreographer#doFrame%' and s.ts between {d} and {d}+200e6")
    fr = q(f"select min(ts+dur) t from actual_frame_timeline_slice where layer_name like '%NotificationShade%' and ts > {d}")
    print(f"касание: relayout через {((rel[0].ts - d) / 1e6) if rel else -1:6.1f} мс ({(rel[0].dur / 1e6) if rel else 0:4.1f} мс), "
          f"первое движение через {((mv[0].ts - d) / 1e6) if mv else -1:5.1f} мс, долгий кадр {((mx[0].m or 0) / 1e6):5.1f} мс, "
          f"первый кадр шторки через {((fr[0].t - d) / 1e6) if fr and fr[0].t else -1:5.1f} мс")
tp.close()
