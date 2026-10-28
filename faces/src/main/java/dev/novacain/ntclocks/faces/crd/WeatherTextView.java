package dev.novacain.ntclocks.faces.crd;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/** Заглушка погоды crDroid: на стоковой Nothing OS их провайдера погоды нет — вид всегда скрыт. */
public class WeatherTextView extends TextView {
    public WeatherTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setVisibility(View.GONE);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(View.GONE);
    }
}
