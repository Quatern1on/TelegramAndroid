#version 310 es

precision highp float;

layout (local_size_x = 8, local_size_y = 8, local_size_z = 1) in;

struct ParticleAttributes {
    vec4 color;
    vec2 position;
    vec2 velocity;
    float lifetime;
};

layout (std140, binding = 0) writeonly restrict buffer ParticleBuffer {
    ParticleAttributes particles[];
};

uniform sampler2D uView;
uniform float uLeft;
uniform float uTop;
uniform uint uWidth;
uniform uint uHeight;
uniform uint uStride;

const float PHI = 1.61803398874989484820459;// ╬ж = Golden Ratio

float gold_noise(in vec2 xy, in float seed) {
    return fract(tan(distance(xy * PHI, xy) * seed) * xy.x);
}

void main() {
    if (gl_GlobalInvocationID.x < uWidth && gl_GlobalInvocationID.y < uHeight) {
        uint i = gl_GlobalInvocationID.x * uHeight + gl_GlobalInvocationID.y;

        vec2 UV;
        UV.x = (float(gl_GlobalInvocationID.x) + 0.5) / float(uWidth);
        UV.y = (float(gl_GlobalInvocationID.y) + 0.5) / float(uHeight);

        vec2 position = vec2(uLeft + float(gl_GlobalInvocationID.x * uStride), uTop + float(gl_GlobalInvocationID.y * uStride));

        float velocityT = gold_noise(position, 0.1) * 6.2831853;
        float velocityR = (1.0 + gold_noise(position, 0.2)) * 63.0 * float(uStride);

        particles[i].color = texture(uView, UV);
        particles[i].position = position;
        particles[i].velocity = vec2(cos(velocityT) * velocityR, sin(velocityT) * velocityR);
        particles[i].lifetime = 0.7 + gold_noise(position, 0.3) * 0.8;
    }
}
