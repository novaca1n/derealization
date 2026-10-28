package dev.novacain.ntclocks.anim;

/**
 * Физика прямоугольников с вращением для «Гравитации» — порт Box2D-lite (Erin Catto, zlib-лицензия): столкновение
 * «коробка — коробка» по разделяющей оси с отсечением ребра (до двух точек контакта), последовательные импульсы с
 * накоплением и тёплым стартом (на этом держатся устойчивые стопки), трение. Сверх оригинала — упругость (отскок при
 * сильном ударе) и лёгкое затухание. Координаты экрана в метрах (y вниз), угол — по часовой, как у Matrix.postRotate.
 * В шаге нет выделений памяти: тела, контакты и пары заведены заранее.
 */
final class Physics {
    static final class Body {
        float x, y, rot, vx, vy, w;          // положение центра, угол, скорость, угловая скорость
        float hx, hy;                        // половины ширины и высоты
        float friction = 0.45f, invMass, invI;

        void set(float width, float height, float mass) {
            hx = width / 2f;
            hy = height / 2f;
            if (mass > 0f) {
                invMass = 1f / mass;
                invI = 12f / (mass * (width * width + height * height));
            } else {
                invMass = 0f;
                invI = 0f;
            }
        }
    }

    private static final class Contact {
        float px, py, nx, ny, sep, pn, pt, massN, massT, bias;
        int feature;

        void copy(Contact o) {
            px = o.px; py = o.py; nx = o.nx; ny = o.ny; sep = o.sep; pn = o.pn; pt = o.pt;
            massN = o.massN; massT = o.massT; bias = o.bias; feature = o.feature;
        }
    }

    private static final class Arbiter {
        final Contact[] c = {new Contact(), new Contact()};
        final Contact[] merged = {new Contact(), new Contact()};
        int n;
    }

    final Body[] bodies;
    final int count;
    private final Arbiter[] arb;
    private final Contact[] fresh = {new Contact(), new Contact()};
    float gx, gy = 9.8f;
    float restitution = 0.22f, linearDamping = 0.08f, angularDamping = 0.25f;
    int iterations = 10;

    Physics(int n) {
        count = n;
        bodies = new Body[n];
        for (int i = 0; i < n; i++) bodies[i] = new Body();
        arb = new Arbiter[n * n];
    }

    private Arbiter pair(int i, int j) {
        Arbiter a = arb[i * count + j];
        if (a == null) arb[i * count + j] = a = new Arbiter();
        return a;
    }

    void step(float dt) {
        float invDt = 1f / dt;
        // широкая фаза — все пары (тел пара десятков), с отсевом по описанным окружностям
        for (int i = 0; i < count; i++) {
            Body a = bodies[i];
            for (int j = i + 1; j < count; j++) {
                Body b = bodies[j];
                if (a.invMass == 0f && b.invMass == 0f) continue;
                float dx = b.x - a.x, dy = b.y - a.y;
                float r = (float) (Math.hypot(a.hx, a.hy) + Math.hypot(b.hx, b.hy));
                Arbiter ar = arb[i * count + j];
                if (dx * dx + dy * dy > r * r) {
                    if (ar != null) ar.n = 0;
                    continue;
                }
                int n = collide(fresh, a, b);
                if (n == 0) {
                    if (ar != null) ar.n = 0;
                    continue;
                }
                ar = pair(i, j);
                update(ar, n);
            }
        }
        // силы
        float ld = Math.max(0f, 1f - linearDamping * dt), ad = Math.max(0f, 1f - angularDamping * dt);
        for (int i = 0; i < count; i++) {
            Body b = bodies[i];
            if (b.invMass == 0f) continue;
            b.vx = (b.vx + dt * gx) * ld;
            b.vy = (b.vy + dt * gy) * ld;
            b.w *= ad;
        }
        for (int i = 0; i < count; i++)
            for (int j = i + 1; j < count; j++) {
                Arbiter ar = arb[i * count + j];
                if (ar != null && ar.n > 0) preStep(ar, bodies[i], bodies[j], invDt);
            }
        for (int it = 0; it < iterations; it++)
            for (int i = 0; i < count; i++)
                for (int j = i + 1; j < count; j++) {
                    Arbiter ar = arb[i * count + j];
                    if (ar != null && ar.n > 0) applyImpulse(ar, bodies[i], bodies[j]);
                }
        for (int i = 0; i < count; i++) {
            Body b = bodies[i];
            if (b.invMass == 0f) continue;
            b.x += dt * b.vx;
            b.y += dt * b.vy;
            b.rot += dt * b.w;
        }
    }

    /** Новые контакты пары; совпавшие по признаку (паре рёбер) наследуют накопленные импульсы — тёплый старт. */
    private void update(Arbiter ar, int n) {
        for (int i = 0; i < n; i++) {
            Contact nw = fresh[i], m = ar.merged[i];
            m.copy(nw);
            m.pn = 0f;
            m.pt = 0f;
            for (int j = 0; j < ar.n; j++) {
                if (ar.c[j].feature == nw.feature) {
                    m.pn = ar.c[j].pn;
                    m.pt = ar.c[j].pt;
                    break;
                }
            }
        }
        for (int i = 0; i < n; i++) ar.c[i].copy(ar.merged[i]);
        ar.n = n;
    }

    private void preStep(Arbiter ar, Body b1, Body b2, float invDt) {
        for (int i = 0; i < ar.n; i++) {
            Contact c = ar.c[i];
            float r1x = c.px - b1.x, r1y = c.py - b1.y, r2x = c.px - b2.x, r2y = c.py - b2.y;
            float rn1 = r1x * c.nx + r1y * c.ny, rn2 = r2x * c.nx + r2y * c.ny;
            float kN = b1.invMass + b2.invMass + b1.invI * (r1x * r1x + r1y * r1y - rn1 * rn1) + b2.invI * (r2x * r2x + r2y * r2y - rn2 * rn2);
            c.massN = 1f / kN;
            float tx = c.ny, ty = -c.nx;
            float rt1 = r1x * tx + r1y * ty, rt2 = r2x * tx + r2y * ty;
            float kT = b1.invMass + b2.invMass + b1.invI * (r1x * r1x + r1y * r1y - rt1 * rt1) + b2.invI * (r2x * r2x + r2y * r2y - rt2 * rt2);
            c.massT = 1f / kT;
            c.bias = -0.2f * invDt * Math.min(0f, c.sep + 0.004f);
            // упругость: при сильном ударе — отскок пропорционально скорости сближения
            float dvx = b2.vx - b2.w * r2y - b1.vx + b1.w * r1y, dvy = b2.vy + b2.w * r2x - b1.vy - b1.w * r1x;
            float vn = dvx * c.nx + dvy * c.ny;
            if (vn < -0.6f) c.bias = Math.max(c.bias, -restitution * vn);
            float px = c.pn * c.nx + c.pt * tx, py = c.pn * c.ny + c.pt * ty;
            b1.vx -= b1.invMass * px;
            b1.vy -= b1.invMass * py;
            b1.w -= b1.invI * (r1x * py - r1y * px);
            b2.vx += b2.invMass * px;
            b2.vy += b2.invMass * py;
            b2.w += b2.invI * (r2x * py - r2y * px);
        }
    }

    private void applyImpulse(Arbiter ar, Body b1, Body b2) {
        float friction = (float) Math.sqrt(b1.friction * b2.friction);
        for (int i = 0; i < ar.n; i++) {
            Contact c = ar.c[i];
            float r1x = c.px - b1.x, r1y = c.py - b1.y, r2x = c.px - b2.x, r2y = c.py - b2.y;
            float dvx = b2.vx - b2.w * r2y - b1.vx + b1.w * r1y, dvy = b2.vy + b2.w * r2x - b1.vy - b1.w * r1x;
            float vn = dvx * c.nx + dvy * c.ny;
            float dPn = c.massN * (-vn + c.bias);
            float pn0 = c.pn;
            c.pn = Math.max(pn0 + dPn, 0f);
            dPn = c.pn - pn0;
            float px = dPn * c.nx, py = dPn * c.ny;
            b1.vx -= b1.invMass * px;
            b1.vy -= b1.invMass * py;
            b1.w -= b1.invI * (r1x * py - r1y * px);
            b2.vx += b2.invMass * px;
            b2.vy += b2.invMass * py;
            b2.w += b2.invI * (r2x * py - r2y * px);

            dvx = b2.vx - b2.w * r2y - b1.vx + b1.w * r1y;
            dvy = b2.vy + b2.w * r2x - b1.vy - b1.w * r1x;
            float tx = c.ny, ty = -c.nx;
            float vt = dvx * tx + dvy * ty;
            float dPt = c.massT * -vt;
            float maxPt = friction * c.pn, pt0 = c.pt;
            c.pt = Math.max(-maxPt, Math.min(maxPt, pt0 + dPt));
            dPt = c.pt - pt0;
            px = dPt * tx;
            py = dPt * ty;
            b1.vx -= b1.invMass * px;
            b1.vy -= b1.invMass * py;
            b1.w -= b1.invI * (r1x * py - r1y * px);
            b2.vx += b2.invMass * px;
            b2.vy += b2.invMass * py;
            b2.w += b2.invI * (r2x * py - r2y * px);
        }
    }

    // ───────── столкновение «коробка — коробка» (Box2D-lite Collide.cpp) ─────────

    // признак контакта — пара рёбер: inEdge1 | outEdge1 << 8 | inEdge2 << 16 | outEdge2 << 24
    private static int flip(int f) {
        int in1 = f & 0xff, out1 = (f >> 8) & 0xff, in2 = (f >> 16) & 0xff, out2 = (f >>> 24);
        return in2 | out2 << 8 | in1 << 16 | out1 << 24;
    }

    private final float[] incX = new float[2], incY = new float[2], c1x = new float[2], c1y = new float[2],
            c2x = new float[2], c2y = new float[2];
    private final int[] incF = new int[2], c1f = new int[2], c2f = new int[2];

    private static int clip(float[] ox, float[] oy, int[] of, float[] ix, float[] iy, int[] inf, float nx, float ny, float offset, int edge) {
        int n = 0;
        float d0 = nx * ix[0] + ny * iy[0] - offset, d1 = nx * ix[1] + ny * iy[1] - offset;
        if (d0 <= 0f) { ox[n] = ix[0]; oy[n] = iy[0]; of[n] = inf[0]; n++; }
        if (d1 <= 0f) { ox[n] = ix[1]; oy[n] = iy[1]; of[n] = inf[1]; n++; }
        if (d0 * d1 < 0f) {
            float t = d0 / (d0 - d1);
            ox[n] = ix[0] + t * (ix[1] - ix[0]);
            oy[n] = iy[0] + t * (iy[1] - iy[0]);
            if (d0 > 0f) of[n] = (inf[0] & 0xff00ff00) | edge;              // inEdge1 = edge, inEdge2 = 0
            else of[n] = (inf[1] & 0x00ff00ff) | edge << 8;                  // outEdge1 = edge, outEdge2 = 0
            n++;
        }
        return n;
    }

    /** Ребро второй коробки, наиболее «смотрящее» против нормали опорной грани. */
    private void incident(float hx, float hy, float px, float py, float c, float s, float nx, float ny) {
        float lx = -(c * nx + s * ny), ly = -(-s * nx + c * ny);
        float v0x, v0y, v1x, v1y;
        int f0, f1;
        if (Math.abs(lx) > Math.abs(ly)) {
            if (lx >= 0f) { v0x = hx; v0y = -hy; f0 = 3 << 16 | 4 << 24; v1x = hx; v1y = hy; f1 = 4 << 16 | 1 << 24; }
            else { v0x = -hx; v0y = hy; f0 = 1 << 16 | 2 << 24; v1x = -hx; v1y = -hy; f1 = 2 << 16 | 3 << 24; }
        } else {
            if (ly >= 0f) { v0x = hx; v0y = hy; f0 = 4 << 16 | 1 << 24; v1x = -hx; v1y = hy; f1 = 1 << 16 | 2 << 24; }
            else { v0x = -hx; v0y = -hy; f0 = 2 << 16 | 3 << 24; v1x = hx; v1y = -hy; f1 = 3 << 16 | 4 << 24; }
        }
        incX[0] = px + c * v0x - s * v0y;
        incY[0] = py + s * v0x + c * v0y;
        incX[1] = px + c * v1x - s * v1y;
        incY[1] = py + s * v1x + c * v1y;
        incF[0] = f0;
        incF[1] = f1;
    }

    /** Нормаль — от A к B. */
    private int collide(Contact[] out, Body a, Body b) {
        float ca = (float) Math.cos(a.rot), sa = (float) Math.sin(a.rot), cb = (float) Math.cos(b.rot), sb = (float) Math.sin(b.rot);
        float dpx = b.x - a.x, dpy = b.y - a.y;
        float dAx = ca * dpx + sa * dpy, dAy = -sa * dpx + ca * dpy;
        float dBx = cb * dpx + sb * dpy, dBy = -sb * dpx + cb * dpy;
        float a11 = Math.abs(ca * cb + sa * sb), a21 = Math.abs(-sa * cb + ca * sb);
        float a12 = Math.abs(-ca * sb + sa * cb), a22 = Math.abs(sa * sb + ca * cb);
        float faceAx = Math.abs(dAx) - a.hx - (a11 * b.hx + a12 * b.hy);
        float faceAy = Math.abs(dAy) - a.hy - (a21 * b.hx + a22 * b.hy);
        if (faceAx > 0f || faceAy > 0f) return 0;
        float faceBx = Math.abs(dBx) - (a11 * a.hx + a21 * a.hy) - b.hx;
        float faceBy = Math.abs(dBy) - (a12 * a.hx + a22 * a.hy) - b.hy;
        if (faceBx > 0f || faceBy > 0f) return 0;

        int axis = 0;
        float sep = faceAx, nx = dAx > 0f ? ca : -ca, ny = dAx > 0f ? sa : -sa;
        if (faceAy > 0.95f * sep + 0.01f * a.hy) { axis = 1; sep = faceAy; nx = dAy > 0f ? -sa : sa; ny = dAy > 0f ? ca : -ca; }
        if (faceBx > 0.95f * sep + 0.01f * b.hx) { axis = 2; sep = faceBx; nx = dBx > 0f ? cb : -cb; ny = dBx > 0f ? sb : -sb; }
        if (faceBy > 0.95f * sep + 0.01f * b.hy) { axis = 3; nx = dBy > 0f ? -sb : sb; ny = dBy > 0f ? cb : -cb; }

        float fnx, fny, front, snx, sny, side, negSide, posSide;
        int negEdge, posEdge;
        switch (axis) {
            case 0:
                fnx = nx; fny = ny; front = a.x * fnx + a.y * fny + a.hx;
                snx = -sa; sny = ca; side = a.x * snx + a.y * sny;
                negSide = -side + a.hy; posSide = side + a.hy; negEdge = 3; posEdge = 1;
                incident(b.hx, b.hy, b.x, b.y, cb, sb, fnx, fny);
                break;
            case 1:
                fnx = nx; fny = ny; front = a.x * fnx + a.y * fny + a.hy;
                snx = ca; sny = sa; side = a.x * snx + a.y * sny;
                negSide = -side + a.hx; posSide = side + a.hx; negEdge = 2; posEdge = 4;
                incident(b.hx, b.hy, b.x, b.y, cb, sb, fnx, fny);
                break;
            case 2:
                fnx = -nx; fny = -ny; front = b.x * fnx + b.y * fny + b.hx;
                snx = -sb; sny = cb; side = b.x * snx + b.y * sny;
                negSide = -side + b.hy; posSide = side + b.hy; negEdge = 3; posEdge = 1;
                incident(a.hx, a.hy, a.x, a.y, ca, sa, fnx, fny);
                break;
            default:
                fnx = -nx; fny = -ny; front = b.x * fnx + b.y * fny + b.hy;
                snx = cb; sny = sb; side = b.x * snx + b.y * sny;
                negSide = -side + b.hx; posSide = side + b.hx; negEdge = 2; posEdge = 4;
                incident(a.hx, a.hy, a.x, a.y, ca, sa, fnx, fny);
                break;
        }
        if (clip(c1x, c1y, c1f, incX, incY, incF, -snx, -sny, negSide, negEdge) < 2) return 0;
        if (clip(c2x, c2y, c2f, c1x, c1y, c1f, snx, sny, posSide, posEdge) < 2) return 0;
        int n = 0;
        for (int i = 0; i < 2; i++) {
            float s = fnx * c2x[i] + fny * c2y[i] - front;
            if (s <= 0f) {
                Contact c = out[n++];
                c.sep = s;
                c.nx = nx;
                c.ny = ny;
                c.px = c2x[i] - s * fnx;
                c.py = c2y[i] - s * fny;
                c.feature = axis >= 2 ? flip(c2f[i]) : c2f[i];
            }
        }
        return n;
    }
}
