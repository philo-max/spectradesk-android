from pathlib import Path
from PIL import Image, ImageDraw
import math

OUTPUT = Path(__file__).resolve().parent
SIZES = (512, 192, 144, 96, 72, 48)
BACKGROUND = '#0B1116'
COLORS = ('#FF754F', '#38D8B0', '#8D86FF')
SCALE = 8
VIEWPORT = 108


def artwork(size, transparent=False):
    canvas = Image.new('RGBA', (size * SCALE, size * SCALE), (0, 0, 0, 0) if transparent else BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    unit = size * SCALE / VIEWPORT

    def point(x, y):
        return (round(x * unit), round(y * unit))

    def line(points, fill, width):
        draw.line([point(x, y) for x, y in points], fill=fill, width=round(width * unit), joint='curve')

    def circle(x, y, radius, fill):
        draw.ellipse((*point(x - radius, y - radius), *point(x + radius, y + radius)), fill=fill)

    vertices = ((54, 31), (34, 66), (74, 66))
    line([vertices[0], vertices[1], vertices[2], vertices[0]], '#A9BBC7', 3.5)
    for (x, y), color in zip(vertices, COLORS):
        circle(x, y, 8, BACKGROUND)
        circle(x, y, 6.4, color)
    line([(50, 54), (58, 54)], '#E6F0F5', 2)
    line([(54, 50), (54, 58)], '#E6F0F5', 2)
    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def verify(path, size, transparent):
    with Image.open(path) as image:
        image.load()
        assert image.size == (size, size), (path, image.size)
        assert image.mode == 'RGBA'
        alpha = image.getchannel('A')
        if transparent:
            bounds = alpha.getbbox()
            low, high = size * 0.17, size * 0.83
            assert bounds and min(bounds[:2]) >= low and max(bounds[2:]) <= high, bounds
            pixels = alpha.load()
            radius = size * 0.33
            for y in range(size):
                for x in range(size):
                    if pixels[x, y]:
                        assert math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) <= radius
        else:
            assert alpha.getextrema() == (255, 255)
        print(f'OK {path.name} | {size}x{size} | {path.stat().st_size} bytes')


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for size in SIZES:
        path = OUTPUT / f'secha_icon_{size}.png'
        artwork(size).save(path)
        verify(path, size, False)
    for size in (108, 432):
        path = OUTPUT / f'secha_adaptive_foreground_{size}.png'
        artwork(size, transparent=True).save(path)
        verify(path, size, True)


if __name__ == '__main__':
    main()
