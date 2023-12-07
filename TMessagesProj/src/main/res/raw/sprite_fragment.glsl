#version 310 es

precision highp float;

in vec2 vUV;

out vec4 fragColor;

uniform sampler2D uSprite;

void main() {
    fragColor = texture(uSprite, vUV);
}
