package knoxthor3d;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import me.zed_0xff.zombie_buddy.Patch;
import org.lwjgl.opengl.GL11C;

/**
 * The sky, the weather and the night of Viewpoint's Vanilla pack, at almost no cost.
 *
 * Vanilla (the "normal" pack, the one a handheld runs) draws the sky as a bare gradient. Viewpoint's
 * other packs draw clouds, the sun, the moon and the stars, with ray-marched clouds, an atmosphere and
 * a lens flare a handheld can't pay for. Here Vanilla's own shaders get the cheap part of that as their
 * text is read, each part by its name in Zomboid/Lua/KnoxThor3D_sky.txt (or KNOXTHOR3D_SKY in
 * Zomdroid's environment variables; the file wins):
 * - clouds: a cloud deck of the value noise Viewpoint ships (lib/noise.glsl), as many clouds as the
 *   weather has (uCloudCover), drifting with the wind (uCloudDrift), lit by the sky and the sun, dark
 *   at night. It's placed in the world 80 squares up (low, for its shade to fall in patches the near
 *   world can show), so it passes overhead as you walk;
 * - sun: the glare about the sun, the warm band along the horizon under a low sun, the sun's disc;
 * - moon: the moon from Viewpoint's map of it, lit by the true sun (its phase), as the Default pack has it;
 * - stars: Viewpoint's star catalogue, turning with the night, twinkling low down;
 * - milkyway: the Milky Way's band;
 * - meteors: now and then a shooting star;
 * - rainbow: a rainbow opposite the sun while it rains in sunshine, or while the ground is still wet;
 * - lightning: the game's lightning lights the clouds round the strike;
 * - aurora: the Vivid pack's curtains of light in the north (Kentucky rarely sees one: only when named);
 * - haze: the fog takes the sky's colour, with the sun's glow and the sunset in it (needs sun);
 * - night: the dim parts of the night turn blue-grey, as the eye sees in the dark; lamps keep their colour;
 * - colour: a little vibrance, for the dull colours only;
 * - wet: rain darkens the ground outside and gives it a faint sheen of the sky;
 * - shadows: the deck's shade drifts over the ground in sunshine; the ground goes dark as the sun goes
 *   behind a cloud.
 * "all" is all of these but the aurora, "-name" takes one out ("all -meteors"), "none" turns them all
 * off, the clouds too. With no such file KnoxThor3D_clouds.txt = 1 (or KNOXTHOR3D_CLOUDS) still turns
 * the clouds on by themselves. Vanilla draws its sky over the whole screen before it lights what's in front; the sky
 * is now only shaded where it shows (a depth test against what's drawn), so its parts cost little
 * where little sky is seen. Read when Viewpoint compiles its shaders, at the start; the other packs'
 * shaders are left as they are.
 */
public final class Sky {
    static final String[] PARTS = {"clouds", "sun", "moon", "stars", "milkyway", "meteors", "rainbow", "lightning",
            "aurora", "haze", "night", "colour", "wet", "shadows"};
    static final int CLOUDS = 1, SUN = 1 << 1, MOON = 1 << 2, STARS = 1 << 3, MILKY = 1 << 4, METEORS = 1 << 5,
            RAINBOW = 1 << 6, LIGHTNING = 1 << 7, AURORA = 1 << 8, HAZE = 1 << 9, NIGHT = 1 << 10, COLOUR = 1 << 11,
            WET = 1 << 12, SHADOWS = 1 << 13;
    private static final int ALL = ((1 << PARTS.length) - 1) & ~AURORA;
    private static final int IN_SKY = CLOUDS | SUN | MOON | STARS | MILKY | METEORS | RAINBOW | LIGHTNING | AURORA;
    private static final int IN_LIGHT = WET | SHADOWS;
    private static final int IN_POST = HAZE | NIGHT | COLOUR;

    private static final String MAIN = "void main() {";
    private static final String MARK = "// KnoxThor3D sky";

    // ------------------------------------------------------------------ the cloud deck, the sun's glow

    private static final String CLOUD_DECK =
            "// KnoxThor3D: a light cloud deck, placed in the world 80 squares up\n"
            + "vec4 kt3dClouds(vec3 r) {\n"
            + "  if (r.y < 0.015) return vec4(0.0);\n"
            + "  vec2 p = r.xz / (r.y + 0.10) * 0.9 + (uEye.xz + uSceneOrigin.xz) * (0.9 / 80.0);\n"
            + "  p += uCloudDrift * 2.5 + vec2(uTime, uTime * 0.6) * 0.002;\n"
            + "  float cover = clamp(uCloudCover, 0.0, 1.0);\n"
            + "  float n = fbm(p) * 0.7 + fbm(p * 2.9 + 7.3) * 0.3;\n"
            + "  float lo = mix(0.60, 0.30, cover);\n"
            + "  float a = smoothstep(lo, lo + 0.18, n) * smoothstep(0.015, 0.22, r.y) * mix(0.70, 0.96, cover);\n"
            + "  if (a <= 0.0) return vec4(0.0);\n"
            + "  vec3 sky = mix(uHorizon, uZenith, 0.6);\n"
            + "  float lum = max(dot(sky, vec3(0.2126, 0.7152, 0.0722)), 1.0e-4);\n"
            + "  vec3 sun = dot(uSunDir, uSunDir) > 1.0e-6 ? normalize(uSunDir) : vec3(0.0, 1.0, 0.0);\n"
            + "  float toward = pow(max(dot(r, sun), 0.0), 3.0);\n"
            + "  vec3 lit = vec3(lum) * mix(1.9, 1.15, cover) + uSunColor * uSunStrength * (0.18 + 0.5 * toward);\n"
            + "  vec3 col = lit * (1.0 - 0.35 * smoothstep(lo + 0.08, lo + 0.45, n));\n"
            + "  col = mix(col, sky * 0.55, clamp(uNight, 0.0, 1.0) * 0.8);\n"
            + "  return vec4(col * a, a);\n"
            + "}\n";

    // as Viewpoint's own sky shader has the glare; the band under a low sun is the Vivid pack's
    private static final String GLOW =
            "// KnoxThor3D: the glare about the sun and, while it's low, the warm band along the horizon under it\n"
            + "vec3 kt3dGlow(vec3 r, vec3 sun, vec3 colour, float strength, float day) {\n"
            + "  float mu = max(dot(r, sun), 0.0), h = max(r.y, 0.0);\n"
            + "  vec3 glow = colour * strength * (0.30 * pow(mu, 6.0) * (1.0 - h) + 0.45 * pow(mu, 80.0));\n"
            + "  vec2 across = r.xz / max(length(r.xz), 1.0e-4), toSun = sun.xz / max(length(sun.xz), 1.0e-4);\n"
            + "  float side = pow(max(0.5 + 0.5 * dot(across, toSun), 0.0), 3.0);\n"
            + "  float low = 1.0 - smoothstep(0.0, 0.45, sun.y);\n"
            + "  return glow + colour * vec3(1.0, 0.72, 0.5) * (0.35 * strength * day * low * side * exp(-abs(r.y) / 0.12));\n"
            + "}\n";

    // ------------------------------------------------------------------ Vanilla's sky, in full

    private static final String SKY_INPUTS =
            "in vec3 vRay;\n"
            + "in vec2 vUv;\n"
            + "uniform vec3 uHorizon;\n"
            + "uniform vec3 uZenith;\n"
            + "uniform vec3 uSunDir;\n"
            + "uniform vec3 uSunColor;\n"
            + "uniform float uSunStrength;\n"
            + "uniform float uCloudCover;\n"
            + "uniform float uNight;\n"
            + "uniform float uTime;\n"
            + "uniform vec2 uCloudDrift;\n"
            + "uniform vec3 uEye;\n"
            + "uniform vec3 uSceneOrigin;\n"
            + "uniform vec3 uSkyMoon;\n"
            + "uniform vec3 uSunTrue;\n"
            + "uniform float uMoonFace;\n"
            + "uniform mat3 uStarFrame;\n"
            + "uniform sampler2D uStars;\n"
            + "uniform sampler2D uMoonMap;\n"
            + "uniform float uOvercast;\n"
            + "uniform float uRainStrength;\n"
            + "uniform float uWet;\n"
            + "uniform vec4 uLightning;\n"
            + "out vec4 fragColor;\n"
            + "#include \"lib/noise.glsl\"\n";

    private static final String SKY_PARTS =
            "#if KT3D_MOON\n"
            + "// the moon from Viewpoint's map of it, lit by the true sun (its phase), with a little earthshine\n"
            + "const float KT3D_MOON_EDGE = 0.99965, KT3D_MOON_FULL = 0.99988;\n"
            + "vec3 kt3dMoon(vec3 r, float pixel, out float cover) {\n"
            + "  float c = dot(r, uSkyMoon);\n"
            + "  cover = smoothstep(KT3D_MOON_EDGE, KT3D_MOON_FULL, c) * smoothstep(-0.004, 0.004, r.y);\n"
            + "  if (cover <= 0.0) return vec3(0.0);\n"
            + "  vec3 north = uStarFrame[2] - uSkyMoon * dot(uStarFrame[2], uSkyMoon);\n"
            + "  north = dot(north, north) > 1.0e-8 ? normalize(north) : vec3(0.0, 1.0, 0.0);\n"
            + "  vec3 right = cross(uSkyMoon, north);\n"
            + "  float radius = sqrt(1.0 - KT3D_MOON_EDGE * KT3D_MOON_EDGE);\n"
            + "  vec2 p = vec2(dot(r, right), dot(r, north)) / radius;\n"
            + "  float z = sqrt(max(1.0 - dot(p, p), 0.0));\n"
            + "  vec3 normal = right * p.x + north * p.y - uSkyMoon * z;\n"
            + "  vec2 uv = vec2(0.5 + atan(p.x, z) / 6.2831853, 0.5 - asin(clamp(p.y, -1.0, 1.0)) / 3.1415927);\n"
            + "  float lod = log2(max(0.25 * float(textureSize(uMoonMap, 0).x) * pixel / radius, 1.0));\n"
            + "  vec3 albedo = pow(textureLod(uMoonMap, uv, lod).rgb, vec3(2.2)) / 0.258;\n"
            + "  float incidence = max(dot(normal, uSunTrue), 0.0);\n"
            + "  float lit = 2.0 * incidence / max(incidence + z, 1.0e-3);\n"
            + "  float earthshine = 0.01 * (1.0 + dot(uSkyMoon, uSunTrue));\n"
            + "  return vec3(1.0, 0.97, 0.92) * albedo * (lit + earthshine) * uMoonFace * 6.0;\n"
            + "}\n"
            + "#endif\n"
            + "#if KT3D_STARS\n"
            + "// Viewpoint's star catalogue: each star a point as bright as its magnitude through the air, twinkling low down\n"
            + "const int KT3D_STAR_CELLS = 32, KT3D_STAR_SLOTS = 16;\n"
            + "ivec2 kt3dStarCell(vec3 e) {\n"
            + "  vec3 a = abs(e);\n"
            + "  int face;\n"
            + "  vec2 uv;\n"
            + "  if (a.x >= a.y && a.x >= a.z) {\n"
            + "    face = e.x > 0.0 ? 0 : 1;\n"
            + "    uv = e.yz / a.x;\n"
            + "  } else if (a.y >= a.z) {\n"
            + "    face = e.y > 0.0 ? 2 : 3;\n"
            + "    uv = e.xz / a.y;\n"
            + "  } else {\n"
            + "    face = e.z > 0.0 ? 4 : 5;\n"
            + "    uv = e.xy / a.z;\n"
            + "  }\n"
            + "  ivec2 c = clamp(ivec2(floor((uv * 0.5 + 0.5) * float(KT3D_STAR_CELLS))), ivec2(0), ivec2(KT3D_STAR_CELLS - 1));\n"
            + "  return ivec2(c.x * KT3D_STAR_SLOTS, face * KT3D_STAR_CELLS + c.y);\n"
            + "}\n"
            + "vec3 kt3dStarTint(float bv) {\n"
            + "  return bv < 0.6 ? mix(vec3(0.78, 0.86, 1.0), vec3(1.0, 0.97, 0.93), clamp((bv + 0.3) / 0.9, 0.0, 1.0))\n"
            + "                  : mix(vec3(1.0, 0.97, 0.93), vec3(1.0, 0.8, 0.62), clamp((bv - 0.6) / 0.9, 0.0, 1.0));\n"
            + "}\n"
            + "vec3 kt3dStars(vec3 e, vec3 r, float pixel, float sky) {\n"
            + "  ivec2 cell = kt3dStarCell(e);\n"
            + "  float airmass = 1.0 / max(r.y, 0.05), low = smoothstep(0.5, 0.0, r.y);\n"
            + "  vec3 sum = vec3(0.0);\n"
            + "  for (int k = 0; k < KT3D_STAR_SLOTS; k++) {\n"
            + "    vec4 s = texelFetch(uStars, ivec2(cell.x + k, cell.y), 0);\n"
            + "    if (s.w <= 0.0) break;\n"
            + "    float d = length(e - s.xyz) / pixel;\n"
            + "    if (d > 3.0) continue;\n"
            + "    float magnitude = floor(s.w) / 100.0 - 2.0, bv = fract(s.w) / 0.98 * 2.5 - 0.4;\n"
            + "    float twinkle = 1.0 + 0.6 * low * (noise(vec2(dot(s.xyz, vec3(91.7, 37.3, 13.9)), uTime * 9.0)) - 0.5);\n"
            + "    float peak = 30.0 * pow(10.0, -0.4 * (magnitude + 0.25 * airmass)) * twinkle;\n"
            + "    sum += kt3dStarTint(bv) * max(peak - sky, 0.0) * exp(-0.5 * d * d / 1.3);\n"
            + "  }\n"
            + "  return sum;\n"
            + "}\n"
            + "#endif\n"
            + "#if KT3D_MILKY\n"
            + "// the Milky Way's band, brightest towards the galaxy's centre, its dust lanes darker\n"
            + "vec3 kt3dMilkyWay(vec3 e, float sky) {\n"
            + "  const vec3 POLE = vec3(-0.8676661, -0.1980764, 0.4559838), CENTRE = vec3(-0.0548755, -0.8734371, -0.4838350);\n"
            + "  float sinB = dot(e, POLE);\n"
            + "  vec3 inPlane = normalize(e - POLE * sinB);\n"
            + "  float towards = dot(inPlane, CENTRE), bulge = smoothstep(0.3, 1.0, towards);\n"
            + "  float width = mix(0.12, 0.22, bulge), b = asin(clamp(sinB, -1.0, 1.0));\n"
            + "  if (abs(b) > 2.6 * width) return vec3(0.0);\n"
            + "  float l = atan(dot(inPlane, cross(POLE, CENTRE)), towards);\n"
            + "  vec2 p = vec2(l * 4.0, b * 12.0);\n"
            + "  float dust = smoothstep(0.5, 0.72, fbm(p * 2.0 + vec2(7.1, 3.3))) * exp(-b * b / (0.3 * width * width));\n"
            + "  float glow = exp(-b * b / (width * width)) * (0.35 + 0.65 * fbm(p)) * (1.0 + 2.0 * bulge) * (1.0 - 0.75 * dust);\n"
            + "  return mix(vec3(0.8, 0.86, 1.0), vec3(1.0, 0.9, 0.76), bulge) * max(glow * 0.06 - 0.3 * sky, 0.0);\n"
            + "}\n"
            + "#endif\n"
            + "#if KT3D_METEORS\n"
            + "// now and then a shooting star: a third of the 7-second slots have one, 20 to 70 degrees up\n"
            + "vec3 kt3dMeteor(vec3 r, float pixel) {\n"
            + "  float slot = floor(uTime / 7.0);\n"
            + "  if (hash(vec2(slot, 1.31)) > 0.33) return vec3(0.0);\n"
            + "  float age = (uTime - slot * 7.0 - 5.5 * hash(vec2(slot, 2.17))) / (0.45 + 0.4 * hash(vec2(slot, 5.71)));\n"
            + "  if (age < 0.0 || age > 1.0) return vec3(0.0);\n"
            + "  float az = 6.2831853 * hash(vec2(slot, 3.37)), el = mix(0.35, 1.2, hash(vec2(slot, 4.43)));\n"
            + "  vec3 start = vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az));\n"
            + "  vec3 across = normalize(cross(start, vec3(0.0, 1.0, 0.0))), up = cross(across, start);\n"
            + "  float turn = 6.2831853 * hash(vec2(slot, 6.61));\n"
            + "  vec3 dir = cos(turn) * across + sin(turn) * up;\n"
            + "  float travel = (0.25 + 0.2 * hash(vec2(slot, 7.73))) * age;\n"
            + "  vec3 head = cos(travel) * start + sin(travel) * dir, ahead = cos(travel) * dir - sin(travel) * start;\n"
            + "  float side = abs(dot(r, cross(start, dir))) / pixel, behind = -dot(r - head, ahead) / pixel;\n"
            + "  float tail = min(0.06 + 0.08 * age, travel + 0.005) / pixel;\n"
            + "  float trail = (1.0 - smoothstep(0.0, 1.3, side)) * smoothstep(-1.5, 0.0, behind) * (1.0 - smoothstep(0.0, tail, behind))\n"
            + "      * step(0.0, dot(r, head));\n"
            + "  return vec3(1.0, 0.94, 0.85) * (trail * sin(age * 3.1415927) * 2.5);\n"
            + "}\n"
            + "#endif\n"
            + "#if KT3D_AURORA\n"
            + "// the Vivid pack's curtains of light in the northern night sky\n"
            + "vec3 kt3dAurora(vec3 r) {\n"
            + "  vec3 sum = vec3(0.0);\n"
            + "  for (int i = 0; i < 12; i++) {\n"
            + "    float up = (float(i) + 0.5) / 12.0;\n"
            + "    vec2 p = r.xz / max(r.y, 0.02) * mix(1.0, 1.8, up);\n"
            + "    float line = 3.0 + 0.8 * (noise(vec2(p.x * 0.35 + uTime * 0.03, 3.7)) * 2.0 - 1.0);\n"
            + "    float shimmer = 0.5 + noise(vec2(p.x * 1.3 - uTime * 0.06, 1.1));\n"
            + "    sum += mix(vec3(0.1, 0.9, 0.45), vec3(0.35, 0.2, 0.7), up) * exp(-abs(p.y - line) / 0.15) * shimmer * (1.0 - up);\n"
            + "  }\n"
            + "  return sum * (2.0 / 12.0);\n"
            + "}\n"
            + "#endif\n"
            + "#if KT3D_RAINBOW\n"
            + "vec3 kt3dSpectrum(float x) {\n"
            + "  float hue = (1.0 - clamp(x, 0.0, 1.0)) * 0.78;\n"
            + "  return clamp(abs(mod(hue * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);\n"
            + "}\n"
            + "// a rainbow 42 degrees round the point opposite the sun, violet inside, the fainter second bow outside it\n"
            + "vec3 kt3dRainbow(vec3 r) {\n"
            + "  float a = degrees(acos(clamp(-dot(r, uSunDir), -1.0, 1.0)));\n"
            + "  float x = (a - 40.4) / 2.2, y = (a - 50.2) / 3.4;\n"
            + "  vec3 bow = kt3dSpectrum(x) * smoothstep(-0.5, 0.15, x) * (1.0 - smoothstep(0.85, 1.5, x));\n"
            + "  bow += 0.4 * kt3dSpectrum(1.0 - y) * smoothstep(-0.5, 0.15, y) * (1.0 - smoothstep(0.85, 1.5, y));\n"
            + "  return bow + vec3(0.08) * (1.0 - smoothstep(36.0, 40.5, a)) * smoothstep(20.0, 36.0, a);\n"
            + "}\n"
            + "#endif\n";

    private static final String SKY_MAIN =
            MAIN + "\n"
            + "  vec3 r = normalize(vRay);\n"
            + "  float h = max(r.y, 0.0);\n"
            + "  vec3 sky = mix(uHorizon, uZenith, 1.0 - exp(-h * 3.0));\n"
            + "  float pixel = max(0.5 * length(fwidth(r)), 1.0e-5);\n"
            + "  float lum = dot(sky, vec3(0.2126, 0.7152, 0.0722));\n"
            + "  float night = max(smoothstep(0.3, 0.9, uNight), smoothstep(-0.02, -0.17, uSunTrue.y));   // (uNight is 0.15 at noon)\n"
            + "#if KT3D_SUN\n"
            + "  sky += kt3dGlow(r, uSunDir, uSunColor, uSunStrength, 1.0 - smoothstep(0.3, 0.9, uNight));\n"
            + "#endif\n"
            + "  float moonCover = 0.0;\n"
            + "  vec3 moon = vec3(0.0);\n"
            + "#if KT3D_MOON\n"
            + "  moon = kt3dMoon(r, pixel, moonCover);\n"
            + "#endif\n"
            + "#if KT3D_STARS || KT3D_MILKY || KT3D_AURORA || KT3D_METEORS\n"
            + "  if (r.y > 0.0 && night > 0.0) {\n"
            + "    vec3 e = transpose(uStarFrame) * r;\n"
            + "    vec3 dark = vec3(0.0);\n"
            + "#if KT3D_STARS\n"
            + "    dark += kt3dStars(e, r, pixel, lum);\n"
            + "#endif\n"
            + "#if KT3D_MILKY\n"
            + "    dark += kt3dMilkyWay(e, lum);\n"
            + "#endif\n"
            + "#if KT3D_AURORA\n"
            + "    dark += kt3dAurora(r);\n"
            + "#endif\n"
            + "#if KT3D_METEORS\n"
            + "    dark += kt3dMeteor(r, pixel);\n"
            + "#endif\n"
            + "    sky += dark * (night * smoothstep(0.0, 0.05, r.y) * (1.0 - smoothstep(0.03, 0.15, lum)) * (1.0 - moonCover)\n"
            + "        * (1.0 - 0.85 * clamp(uOvercast, 0.0, 1.0)));   // where the sky itself is dark\n"
            + "  }\n"
            + "#endif\n"
            + "  float disc = 0.0;\n"
            + "#if KT3D_SUN\n"
            + "  disc = smoothstep(0.99992, 0.99997, dot(r, uSunDir)) * smoothstep(-0.004, 0.004, r.y);\n"
            + "#if KT3D_MOON\n"
            + "  if (dot(uSunDir, uSkyMoon) > 0.9999) disc = 0.0;   // the night's light is the moon's, drawn above\n"
            + "#endif\n"
            + "#endif\n"
            + "  vec4 cloud = vec4(0.0);\n"
            + "#if KT3D_CLOUDS\n"
            + "  cloud = kt3dClouds(r);\n"
            + "#endif\n"
            + "  sky = sky * (1.0 - cloud.a) + cloud.rgb;\n"
            + "#if KT3D_LIGHTNING\n"
            + "  if (uLightning.w > 0.0) {\n"
            + "    vec3 to = uLightning.xyz - uEye;\n"
            + "    float toward = dot(to, to) > 1.0 ? pow(max(dot(r, normalize(to)), 0.0), 6.0) : 0.0;\n"
            + "    sky += vec3(0.80, 0.86, 1.0) * (uLightning.w * (0.10 + 1.2 * toward) * (0.35 + 0.65 * cloud.a));\n"
            + "  }\n"
            + "#endif\n"
            + "#if KT3D_RAINBOW\n"
            + "  float rain = clamp(uRainStrength, 0.0, 1.0);\n"
            + "  float shower = max(smoothstep(0.03, 0.25, rain) * (1.0 - smoothstep(0.6, 0.95, rain)),\n"
            + "      0.6 * smoothstep(0.5, 0.95, uWet) * (1.0 - smoothstep(0.0, 0.03, rain)));\n"
            + "  float sunlit = clamp(uSunStrength, 0.0, 1.0) * smoothstep(0.0, 0.06, uSunTrue.y) * (1.0 - smoothstep(0.55, 0.75, uSunDir.y))\n"
            + "      * (1.0 - 0.8 * clamp(uOvercast, 0.0, 1.0));\n"
            + "  if (shower * sunlit > 0.0 && r.y > 0.0) sky += kt3dRainbow(r) * (shower * sunlit * max(lum, 0.05) * 0.8 * smoothstep(0.0, 0.1, r.y));\n"
            + "#endif\n"
            + "  sky += (uSunColor * uSunStrength * disc * 14.0 + moon) * (1.0 - cloud.a);\n"
            + "  fragColor = vec4(sky, 1.0);\n"
            + "}\n";

    // Vanilla's sky as Viewpoint ships it: only the horizon-to-zenith gradient
    private static final String PLAIN_LINE = "fragColor = vec4(mix(uHorizon, uZenith, 1.0 - exp(-h * 3.0)), 1.0);";
    // a sky shader that lays a cloud picture over the sky (Viewpoint's base one): the deck goes where it's empty
    private static final String CLOUD_LINE = "vec4 cloud = uCloudsOn > 0.5 ? texture(uClouds, vUv) : vec4(0.0);";
    private static final String CLOUD_OURS = CLOUD_LINE + "\n  if (cloud.a < 0.002) cloud = kt3dClouds(r);";
    private static final String VERT_LINE = "gl_Position = vec4(p, 0.0, 1.0);";

    // ------------------------------------------------------------------ Vanilla's light pass

    private static final String LIGHT_FOLIAGE = "bool foliage = albedo.a > 0.75;";
    private static final String LIGHT_SHADE = "vec3 color = shade(albedo.rgb, vec4(light, normal.a), foliage ? vec3(0.0, 1.0, 0.0) : n, 1.0);";

    private static final String CLOUD_SHADE =
            "// KnoxThor3D: the shade of the sky's cloud deck on the ground in sunshine (the deck is placed in the world)\n"
            + "uniform vec3 uSkySun;\n"
            + "uniform float uSunStrength;\n"
            + "uniform float uNight;\n"
            + "uniform float uCloudCover;\n"
            + "uniform vec2 uCloudDrift;\n"
            + "uniform float uTime;\n"
            + "uniform vec3 uSceneOrigin;\n"
            + "float kt3dHash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }\n"
            + "float kt3dNoise(vec2 p) {\n"
            + "  vec2 i = floor(p), f = fract(p);\n"
            + "  f = f * f * (3.0 - 2.0 * f);\n"
            + "  return mix(mix(kt3dHash(i), kt3dHash(i + vec2(1.0, 0.0)), f.x), mix(kt3dHash(i + vec2(0.0, 1.0)), kt3dHash(i + vec2(1.0, 1.0)), f.x), f.y);\n"
            + "}\n"
            + "float kt3dCloudShade(vec3 pos, float outdoors) {\n"
            + "  float sun = clamp(uSunStrength, 0.0, 1.0) * (1.0 - smoothstep(0.3, 0.9, uNight)) * clamp(outdoors, 0.0, 1.0);\n"
            + "  if (sun <= 0.0 || uSkySun.y < 0.02) return 1.0;\n"
            + "  vec2 p = (pos.xz + uSceneOrigin.xz) * (0.9 / 80.0) + uSkySun.xz / (uSkySun.y + 0.10) * 0.9;\n"
            + "  p += uCloudDrift * 2.5 + vec2(uTime, uTime * 0.6) * 0.002;\n"
            + "  float v = 0.0, a = 0.5;\n"
            + "  for (int i = 0; i < 4; i++) {\n"
            + "    v += a * kt3dNoise(p);\n"
            + "    p = p * 2.03 + vec2(17.0, 9.0);\n"
            + "    a *= 0.5;\n"
            + "  }\n"
            + "  // the deck's first octaves; its finest one, and its second layer, at their mean\n"
            + "  float cover = clamp(uCloudCover, 0.0, 1.0), lo = mix(0.60, 0.30, cover);\n"
            + "  float deck = smoothstep(lo, lo + 0.18, (v + 0.016) * 0.7 + 0.145) * mix(0.70, 0.96, cover);\n"
            + "  return 1.0 - 0.45 * deck * sun;\n"
            + "}\n";

    private static final String WET_GROUND =
            "// KnoxThor3D: rain darkens the ground outside and gives it a faint sheen of the sky\n"
            + "uniform float uWet;\n"
            + "vec3 kt3dWet(vec3 color, vec3 n, vec3 pos, float outdoors, bool foliage) {\n"
            + "  float wet = clamp(uWet, 0.0, 1.0) * smoothstep(0.75, 0.95, n.y) * clamp(outdoors, 0.0, 1.0);\n"
            + "  if (wet <= 0.0) return color;\n"
            + "  if (foliage) return color * (1.0 - 0.15 * wet);\n"
            + "  float grazing = 1.0 - max(dot(n, normalize(uEye - pos)), 0.0);\n"
            + "  float fresnel = grazing * grazing * grazing * grazing * grazing;\n"
            + "  return color * (1.0 - 0.3 * wet) + uFogColor * ((0.03 + 0.35 * fresnel) * wet);\n"
            + "}\n";

    // ------------------------------------------------------------------ Vanilla's post pass

    private static final String POST_OUT = "out vec4 fragColor;";
    private static final String POST_FOG = "color = mix(color, uFogColor, fog);";
    private static final String POST_TONE = "color = shoulder(max(color, vec3(0.0)) * uExposure);";
    private static final String POST_INPUTS =
            "uniform vec3 uSkySun;\n"
            + "uniform vec3 uSkySunColor;\n"
            + "uniform float uSkyGlow;\n"
            + "uniform float uNight;\n";
    private static final String HAZE_FOG =
            "color = mix(color, uFogColor + kt3dGlow(normalize(scenePos(vUv, depth) - uEye), uSkySun, uSkySunColor, uSkyGlow,\n"
            + "        1.0 - smoothstep(0.3, 0.9, uNight)), fog);   // KnoxThor3D: the fog in the sky's own colour";
    private static final String NIGHT_SHIFT =
            "// KnoxThor3D: the dim parts of the night blue-grey, lamps keep their colour (uNight is 0.15 at noon)\n"
            + "  float kt3dLum = dot(color, vec3(0.2126, 0.7152, 0.0722));\n"
            + "  color = mix(color, kt3dLum * vec3(0.80, 1.0, 1.45),\n"
            + "      smoothstep(0.3, 0.9, uNight) * 0.55 * (1.0 - smoothstep(0.03, 0.35, kt3dLum * uExposure)));\n  ";
    private static final String VIBRANCE =
            "\n  // KnoxThor3D: a little vibrance, for the dull colours only\n"
            + "  float kt3dTop = max(color.r, max(color.g, color.b));\n"
            + "  float kt3dDull = 1.0 - (kt3dTop - min(color.r, min(color.g, color.b))) / max(kt3dTop, 1.0e-4);\n"
            + "  color = max(mix(vec3(dot(color, vec3(0.2126, 0.7152, 0.0722))), color, 1.0 + 0.3 * kt3dDull), vec3(0.0));";

    private static int parts = -1;
    private static long partsRead;
    private static volatile boolean plainSky, farSky;
    private static boolean loggedSky, loggedLight, loggedPost, loggedMissing;

    private Sky() {}

    /** The parts that are on, read again when Viewpoint compiles its shaders anew (a pack's options changed). */
    static int parts() {
        long now = System.currentTimeMillis();
        if (parts < 0 || now - partsRead > 2000) {
            partsRead = now;
            int p = 0;
            if (Main.onZomdroid()) {
                try {
                    String c = setting("KNOXTHOR3D_CLOUDS", "KnoxThor3D_clouds.txt");
                    if (c != null && Integer.parseInt(c.trim()) == 1) p |= CLOUDS;
                } catch (Throwable ignored) {
                }
                String s = setting("KNOXTHOR3D_SKY", "KnoxThor3D_sky.txt");
                if (s != null) p = parse(s, p);
                if ((p & SUN) == 0) p &= ~HAZE;
            }
            if (p != parts && (parts >= 0 || p != 0)) {
                Main.log("sky: " + names(p));
                loggedSky = loggedLight = loggedPost = false;
            }
            parts = p;
        }
        return parts;
    }

    /** The file in Zomboid/Lua if there is one, else the environment variable, else null. */
    private static String setting(String env, String file) {
        try {
            File f = new File(System.getProperty("user.home"), "Zomboid/Lua/" + file);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
        try {
            String e = System.getenv(env);
            if (e != null && !e.trim().isEmpty()) return e;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** "all -meteors", "sun moon stars", "none"... onto the parts already on. */
    static int parse(String s, int p) {
        for (String w : s.toLowerCase(Locale.ROOT).split("[\\s,;]+")) {
            boolean off = w.startsWith("-");
            if (off) w = w.substring(1);
            if (w.isEmpty()) continue;
            if (w.equals("none")) {
                p = 0;
                continue;
            }
            int bit = w.equals("all") ? ALL : w.equals("color") ? COLOUR : 0;
            for (int i = 0; i < PARTS.length && bit == 0; i++) if (PARTS[i].equals(w)) bit = 1 << i;
            p = off ? p & ~bit : p | bit;
        }
        return p;
    }

    static String names(int p) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < PARTS.length; i++) {
            if ((p & 1 << i) == 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(PARTS[i]);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private static boolean is(String name, String file) {
        return name.equals(file) || name.endsWith("/" + file);
    }

    /** Shaders.read: Vanilla's sky, light and post shaders get the parts that are on. */
    public static String shader(String name, String text) {
        if (text == null || name == null) return text;
        int p = parts();
        if (p == 0) {
            if (is(name, "sky.frag")) plainSky = false;
            if (is(name, "sky.vert")) farSky = false;
            return text;
        }
        if (is(name, "sky.frag")) return sky(name, text, p);
        if (is(name, "sky.vert")) return skyVert(text);
        if (is(name, "light.frag") && (p & IN_LIGHT) != 0) return light(text, p);
        if (is(name, "post.frag") && (p & IN_POST) != 0) return post(text, p);
        return text;
    }

    private static String sky(String name, String text, int p) {
        if (text.contains(MARK)) {
            plainSky = true;
            return text;
        }
        plainSky = false;
        if (text.contains(PLAIN_LINE) && text.contains("#version") && !text.contains("uCloudCover")) {
            if ((p & IN_SKY) == 0) return text;
            plainSky = true;
            if (!loggedSky) {
                loggedSky = true;
                Main.log("sky: Vanilla's sky gets " + names(p & IN_SKY));
            }
            int line = text.indexOf('\n', text.indexOf("#version"));
            StringBuilder s = new StringBuilder(text.substring(0, line + 1)).append(MARK).append('\n');
            define(s, "KT3D_CLOUDS", p & CLOUDS);
            define(s, "KT3D_SUN", p & SUN);
            define(s, "KT3D_MOON", p & MOON);
            define(s, "KT3D_STARS", p & STARS);
            define(s, "KT3D_MILKY", p & MILKY);
            define(s, "KT3D_METEORS", p & METEORS);
            define(s, "KT3D_RAINBOW", p & RAINBOW);
            define(s, "KT3D_LIGHTNING", p & LIGHTNING);
            define(s, "KT3D_AURORA", p & AURORA);
            return s.append(SKY_INPUTS).append(CLOUD_DECK).append(GLOW).append(SKY_PARTS).append(SKY_MAIN).toString();
        }
        if ((p & CLOUDS) != 0 && text.contains(CLOUD_LINE) && text.contains(MAIN) && text.contains("lib/noise.glsl")
                && text.contains(POST_OUT) && !text.contains("kt3dClouds")) {
            if (!loggedSky) {
                loggedSky = true;
                Main.log("sky: a light cloud deck in the sky");
            }
            String inputs = (text.contains("uniform vec3 uEye;") ? "" : "uniform vec3 uEye;\n")
                    + (text.contains("uniform vec3 uSceneOrigin;") ? "" : "uniform vec3 uSceneOrigin;\n");
            String t = text.replace(POST_OUT, inputs + POST_OUT);
            int at = t.indexOf(MAIN);
            return (t.substring(0, at) + CLOUD_DECK + t.substring(at)).replace(CLOUD_LINE, CLOUD_OURS);
        }
        if (!loggedMissing) {
            loggedMissing = true;
            Main.log("sky: the sky shader " + name + " differs, left as it is; it reads:");
            String t = text.replace('\n', '|');
            for (int i = 0; i < t.length(); i += 900) Main.log("sky> " + t.substring(i, Math.min(t.length(), i + 900)));
        }
        return text;
    }

    private static void define(StringBuilder s, String name, int on) {
        s.append("#define ").append(name).append(on != 0 ? " 1\n" : " 0\n");
    }

    /** The sky's triangle on the far plane, so a depth test can leave out what's drawn in front of it. */
    private static String skyVert(String text) {
        if (text.contains("kt3d")) {
            farSky = true;
            return text;
        }
        farSky = text.contains(VERT_LINE) && text.contains("vRay");
        return farSky ? text.replace(VERT_LINE, "gl_Position = vec4(p, 1.0, 1.0);   // kt3d: on the far plane") : text;
    }

    private static String light(String text, int p) {
        if (text.contains("kt3d")) return text;
        if (!text.contains(LIGHT_FOLIAGE) || !text.contains(LIGHT_SHADE) || !text.contains(MAIN) || !text.contains("uEngineLight")) {
            if (!loggedLight) {
                loggedLight = true;
                Main.log("sky: the light shader isn't Vanilla's, left as it is (no wet ground, no cloud shadows)");
            }
            return text;
        }
        if (!loggedLight) {
            loggedLight = true;
            Main.log("sky: Vanilla's light pass gets " + names(p & IN_LIGHT));
        }
        String code = ((p & SHADOWS) != 0 ? CLOUD_SHADE : "") + ((p & WET) != 0 ? WET_GROUND : "");
        int at = text.indexOf(MAIN);
        String t = text.substring(0, at) + code + text.substring(at);
        if ((p & SHADOWS) != 0) t = t.replace(LIGHT_FOLIAGE, LIGHT_FOLIAGE + "\n  light *= kt3dCloudShade(pos, normal.a);");
        if ((p & WET) != 0) t = t.replace(LIGHT_SHADE, LIGHT_SHADE + "\n  color = kt3dWet(color, n, pos, normal.a, foliage);");
        return t;
    }

    private static String post(String text, int p) {
        if (text.contains("kt3d")) return text;
        if (!text.contains(POST_OUT) || !text.contains(POST_FOG) || !text.contains(POST_TONE) || !text.contains("uFogColor")) {
            if (!loggedPost) {
                loggedPost = true;
                Main.log("sky: the post shader isn't Vanilla's, left as it is (no haze, night colour or vibrance)");
            }
            return text;
        }
        if (!loggedPost) {
            loggedPost = true;
            Main.log("sky: Vanilla's post pass gets " + names(p & IN_POST));
        }
        String t = text.replace(POST_OUT, POST_OUT + "\n" + POST_INPUTS + ((p & HAZE) != 0 ? GLOW : ""));
        if ((p & HAZE) != 0) t = t.replace(POST_FOG, HAZE_FOG);
        if ((p & NIGHT) != 0) t = t.replace(POST_TONE, NIGHT_SHIFT + POST_TONE);
        if ((p & COLOUR) != 0) t = t.replace(POST_TONE, POST_TONE + VIBRANCE);
        return t;
    }

    @Patch(className = "viewpoint.platform.Shaders", methodName = "read")
    public static final class Source {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) String name, @Patch.Return(readOnly = false) String text) {
            text = Sky.shader(name, text);
        }
    }

    // ------------------------------------------------------------------ the sky only where it shows

    /*
     * Viewpoint draws Vanilla's sky as one triangle over the whole screen, depth test off, and the light
     * pass then covers every pixel something was drawn at. With the sky's triangle on the far plane and
     * the depth test on (less or equal, no depth writes), the pixels in front of it are left out before
     * the sky shader runs for them. Only while the sky's shader is Vanilla's, made here.
     */
    private static boolean inSky;
    private static int depthFunc = GL11C.GL_LESS;

    public static void skyStart() {
        inSky = plainSky && farSky;
    }

    public static void skyEnd() {
        inSky = false;
    }

    public static void triangleStart() {
        if (!inSky) return;
        depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        GL11C.glDepthFunc(GL11C.GL_LEQUAL);
        GL11C.glEnable(GL11C.GL_DEPTH_TEST);
    }

    public static void triangleEnd() {
        if (!inSky) return;
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthFunc(depthFunc);
    }

    @Patch(className = "viewpoint.render.SkyPass", methodName = "sky")
    public static final class SkyDraw {
        @Patch.OnEnter
        public static void enter() {
            Sky.skyStart();
        }

        @Patch.OnExit
        public static void exit() {
            Sky.skyEnd();
        }
    }

    @Patch(className = "viewpoint.platform.Gl", methodName = "screenTriangle")
    public static final class Triangle {
        @Patch.OnEnter
        public static void enter() {
            Sky.triangleStart();
        }

        @Patch.OnExit
        public static void exit() {
            Sky.triangleEnd();
        }
    }
}
