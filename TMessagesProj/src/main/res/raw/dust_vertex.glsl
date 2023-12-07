#version 310 es

precision highp float;

layout(location = 0) in vec4 aColor;
layout(location = 1) in vec2 aPosition;
layout(location = 2) in vec2 aVelocity;
layout(location = 3) in float aLifetime;

out vec4 vColor;

uniform mat4 uMVP;
uniform float uTime;
uniform int uWidth;
uniform int uHeight;
uniform float uSize;

float ease(in float x) {
    if (x < 0.8) {
        x = x + 0.2;
        return x * x * 0.5 - 0.02;
    } else {
        return x - 0.32;
    }
}

void main() {
    vColor = aColor;

    int localX = gl_VertexID / uHeight;
    float localXNorm = float(localX) / float(uWidth);

    float t = ease(max(uTime - localXNorm * 0.4, 0.0));

    vColor.a *= clamp((aLifetime - t) / 0.3, 0.0, 1.0);

    vec2 position = aPosition + aVelocity * t + vec2(0.0, -180.0 * uSize) * t * t * 0.5;

    gl_PointSize = uSize + 1.0;
    gl_Position = uMVP * vec4(position, 0.0, 1.0);

    if (vColor.a < 0.003) {
        //Hack for discarding the particle
        gl_Position = vec4(9999999.0, 0.0, 0.0, 1.0);
    }
}
