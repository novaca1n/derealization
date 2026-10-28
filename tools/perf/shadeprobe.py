import sys
from perfetto.trace_processor import TraceProcessor
tp=TraceProcessor(trace=sys.argv[1])
mv=sorted((r.d/1e6 for r in tp.query("""select s.dur d from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)
   where p.name like 'com.android.systemui%' and s.name like 'dispatchInputEvent MotionEvent ACTION_MOVE%'""")), reverse=True)
gpu=next(iter(tp.query("""select sum(s.dur)/1e6 g from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)
   where p.name like 'com.android.systemui%' and t.name='GPU completion'"""))).g
df=sorted(r.d/1e6 for r in tp.query("""select s.dur d from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)
   where p.name like 'com.android.systemui%' and s.name like 'DrawFrame%'"""))
print(f"движения: самые долгие {[round(x,1) for x in mv[:3]]} мс; видеочип {gpu:.0f} мс; DrawFrame медиана {df[len(df)//2]:.2f}, 95% {df[int(len(df)*.95)]:.2f}")
tp.close()
