package dev.novacain.ntclocks.faces.crd;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

/** Заглушка значка погоды crDroid — всегда скрыт (см. WeatherTextView). */
public class WeatherImageView extends ImageView {
    public WeatherImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setVisibility(View.GONE);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(View.GONE);
    }
}
