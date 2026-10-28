package dev.novacain.ntclocks.sysui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;

import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.plugins.clocks.ClockMessageBuffers;
import com.android.systemui.plugins.clocks.ClockMetadata;
import com.android.systemui.plugins.clocks.ClockPickerConfig;
import com.android.systemui.plugins.clocks.ClockProvider;
import com.android.systemui.plugins.clocks.ClockSettings;

import java.util.ArrayList;
import java.util.List;

import dev.novacain.ntclocks.faces.Clocks;

/** Поставщик часов модуля — встаёт в ClockRegistry рядом с NTClockProvider Nothing. */
final class NcProvider implements ClockProvider {
    private final Context context;

    NcProvider(Context context) {
        this.context = context;
    }

    @Override
    public List<ClockMetadata> getClocks() {
        List<ClockMetadata> list = new ArrayList<>();
        for (String id : Clocks.IDS) list.add(new ClockMetadata(id, false, null));
        return list;
    }

    @Override
    public ClockController createClock(ClockSettings settings) {
        String id = settings.getClockId();
        return new NcController(context, Clocks.isOurs(id) ? id : Clocks.DOTS);
    }

    @Override
    public ClockPickerConfig getClockPickerConfig(ClockSettings settings) {
        String id = settings.getClockId() != null ? settings.getClockId() : Clocks.DOTS;
        return new ClockPickerConfig(id, Clocks.title(id), "", new ColorDrawable(0));
    }

    @Override
    public void initialize(ClockMessageBuffers buffers) {
    }
}
