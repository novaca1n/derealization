"""Разбор FrameTimeline так, как видит глаз. По каждому окну с анимацией: кадров, пропусков (между показами
соседних кадров ≥ 1.5 периода экрана, внутри движения — паузы > 60 мс не считаем) и задержка от начала кадра до
показа (медиана, 95 %). Экран 120 Гц: период 8.33 мс, пропуск — интервал ≥ 12.5 мс."""
import statistics
import sys
from perfetto.trace_processor import TraceProcessor

P = 1000 / 120
tp = TraceProcessor(trace=sys.argv[1])
layers = {}
for r in tp.query("""select layer_name l, ts, dur from actual_frame_timeline_slice
                     where layer_name is not null and layer_name != '' and dur > 0 order by ts"""):
    layers.setdefault(r.l, []).append((r.ts, r.dur))
tot_f = tot_d = 0
for l, fr in sorted(layers.items(), key=lambda x: -len(x[1])):
    if len(fr) < 20:
        continue
    pres = sorted(t + d for t, d in fr)
    gaps = [(b - a) / 1e6 for a, b in zip(pres, pres[1:])]
    moving = [g for g in gaps if g < 60]
    drops = sum(round(g / P) - 1 for g in moving if g >= 1.5 * P)
    lat = sorted(d / 1e6 for _, d in fr)
    tot_f += len(moving); tot_d += drops
    print(f"{drops:3d} пропусков на {len(moving):4d} кадров ({100 * drops / max(1, len(moving) + drops):4.1f} %), "
          f"задержка {statistics.median(lat):5.1f}/{lat[int(len(lat) * 0.95)]:5.1f} мс  {l.split('#')[0][-50:]}")
print(f"ВСЕГО: {tot_d} пропусков на {tot_f} кадров ({100 * tot_d / max(1, tot_f + tot_d):.2f} %)")
tp.close()
