import gg.phase.world.gen.layout.WorldLayout;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Vertical slice: java CrossSection <seed> <out.png> <x0> <z0> <x1> <z1> (terrain + giant caverns, not vanilla caves) */
public class CrossSection {
	public static void main(String[] a) throws Exception {
		long seed = Long.parseLong(a[0]);
		WorldLayout L = WorldLayout.get(seed);
		double x0 = Double.parseDouble(a[2]), z0 = Double.parseDouble(a[3]), x1 = Double.parseDouble(a[4]), z1 = Double.parseDouble(a[5]);
		int w = 1500, h = 680; // 2 blocks per pixel vertically: -64..1296
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < w; i++) {
			double t = i / (double) (w - 1);
			int x = (int) Math.round(x0 + (x1 - x0) * t), z = (int) Math.round(z0 + (z1 - z0) * t);
			WorldLayout.Column c = L.column(x, z);
			for (int j = 0; j < h; j++) {
				int y = 1296 - j * 2;
				double d = L.terrainDensity(x, y, z, c);
				double cav = L.cavern(x, y, z, c);
				int col;
				if (d > 0 && cav > 0) col = y < 0 ? 0x4a4a55 : 0x8a8580;
				else if (d > 0) col = 0x201814;              // carved cavern
				else if (y <= 63) col = 0x2a5aa0;
				else if (!Float.isNaN(c.water) && y <= c.water) col = 0x4a8fe0;
				else col = 0xc8ddf0;
				if (d > 0 && d < 0.5 && cav > 0) col = 0x3f8a3a;
				img.setRGB(i, j, col);
			}
		}
		ImageIO.write(img, "png", new File(a[1]));
	}
}
