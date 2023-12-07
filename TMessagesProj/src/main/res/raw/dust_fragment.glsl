#version 310 es

precision highp float;

in vec4 vColor;

out vec4 fragColor;

void main() {
    fragColor = vec4(vColor);
}
