#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// the soft particle fragment stage, carried over from the pre-26.1 cascade_soft shaders: the quad fades
// out where it approaches scene geometry, read from a depth copy of the main target, so smoke has no hard
// clip line. one file serves the lit and unlit pipelines, the particle vertex stage folds the lightmap
// into vertexColor before it arrives here

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const float FADE_DISTANCE = 1.5;

// turn a non linear depth buffer value into a positive view space distance using the projection matrix.
// the depth range depends on the backend's clip control: a zero to one range stores ndc z as is, the classic
// minus one to one range remaps it. the two matrices are told apart by ProjMat[2][2], which stays below one
// in magnitude for zero to one and above one for the classic range, at any sane near and far
float linearize(float depth) {
    float ndc = abs(ProjMat[2][2]) > 1.0 ? depth * 2.0 - 1.0 : depth;
    return ProjMat[3][2] / (ProjMat[2][2] + ndc);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    ivec2 px = ivec2(gl_FragCoord.xy);
    float scene = linearize(texelFetch(DepthSampler, px, 0).r);
    float here = linearize(gl_FragCoord.z);
    float soft = clamp((scene - here) / FADE_DISTANCE, 0.0, 1.0);
    fragColor = vec4(tex.rgb, tex.a * soft);
    if (fragColor.a < 0.001) {
        discard;
    }
}
