package dev.novacain.ntclocks.sysui;

import android.view.View;

import androidx.constraintlayout.widget.ConstraintSet;

import com.android.systemui.plugins.clocks.AodClockBurnInModel;
import com.android.systemui.plugins.clocks.ClockFaceLayout;
import com.android.systemui.plugins.clocks.ClockPreviewConfig;
import com.android.systemui.plugins.clocks.DefaultClockFaceLayout;

import java.util.List;

/**
 * Разметка часов на экране блокировки. ClockSection Nothing задаёт основным часам ЖЁСТКУЮ высоту
 * (R.dimen.small_clock_height) — всё, что крупнее, обрезается. Сразу после этого он зовёт applyConstraints у часов:
 * здесь высота меняется на «по содержимому». Виджеты Nothing и уведомления привязаны к нижнему краю часов
 * (барьер smart_space_barrier_bottom), поэтому сами сдвигаются вниз и ни на что не наезжают.
 * Остальное (превью, сдвиг от выгорания в AOD) — как у стандартной разметки.
 */
final class NcLayout implements ClockFaceLayout {
    private final View view;
    private final DefaultClockFaceLayout base;

    NcLayout(View view) {
        this.view = view;
        this.base = new DefaultClockFaceLayout(view);
    }

    @Override
    public List<View> getViews() {
        return base.getViews();
    }

    @Override
    public ConstraintSet applyConstraints(ConstraintSet constraints) {
        ConstraintSet c = base.applyConstraints(constraints);
        if (view.getId() != View.NO_ID) {
            c.constrainHeight(view.getId(), ConstraintSet.WRAP_CONTENT);
            c.constrainMaxHeight(view.getId(), 0);          // 0 = без ограничения
        }
        return c;
    }

    @Override
    public ConstraintSet applyPreviewConstraints(ClockPreviewConfig config, ConstraintSet constraints, boolean isWidgetExpanded) {
        ConstraintSet c = base.applyPreviewConstraints(config, constraints, isWidgetExpanded);
        if (view.getId() != View.NO_ID) c.constrainHeight(view.getId(), ConstraintSet.WRAP_CONTENT);
        return c;
    }

    @Override
    public void applyAodBurnIn(AodClockBurnInModel model) {
        base.applyAodBurnIn(model);
    }
}
