package gg.phase.cosmetics.pet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Живой слизень одного игрока. Скачет по земле за хозяином короткими прыжками, как желе: перед прыжком
 * приседает, в полёте вытягивается, при приземлении сплющивается и пружинит. Моргает, радуется, грустит,
 * удивляется и засыпает; умеет садиться на плечо, ждать на месте и делать трюки.
 * Всё считается на клиенте каждый тик; поза меняется плавно, отрисовка — SlimeRenderer.
 */
public final class Slime {
	// ── Выражения лица ──
	public static final int NORMAL = 0, BLINK = 1, HAPPY = 2, SAD = 3, WOW = 4, SLEEP = 5;
	public static final String[] FACE_MODEL = { "slime_face", "slime_face_blink", "slime_face_happy", "slime_face_sad", "slime_face_wow", "slime_face_sleep" };

	public enum Act { IDLE, PERCH, STAY, PETTED, FLIP, WOW, DANCE, ROLL, VISIT, SULK, GUARD }

	public final int ownerId;
	/** Точка под слизнем (низ модели) */
	public Vec3 pos, prevPos;
	public float yaw, yawO, pitch, pitchO, roll, rollO;
	/** Желе: sq > 0 — сплющен, sq < 0 — вытянут */
	public float sq, sqO;
	private float sqV;
	public int face = NORMAL;
	/** Для автотеста: принудительное выражение (−1 — как обычно) */
	public int forceFace = -1;
	public String color = "";
	public float age;

	// на плече: «пристёгнут» к плечу и рисуется вместе с хозяином
	public boolean perched;
	public double perchY = PERCH_STAND, perchYO = PERCH_STAND;
	static final double PERCH_STAND = 1.5, PERCH_CROUCH = 1.22;

	public Act act = Act.IDLE;
	private int actTicks, actLen;

	// прыжок
	private boolean hopping;
	private Vec3 hopFrom = Vec3.ZERO, hopTo = Vec3.ZERO;
	private double hopH;
	private int hopT, hopLen, crouchLeft, restLeft;
	private float hopFlip, hopRoll; // трюки в прыжке: сальто и кувырок (градусы за весь прыжок)
	private float spinYaw;          // танец: поворот на каждом прыжке

	// падение (опору сломали) и вода
	private boolean falling;
	private double fallVy;

	// настроение
	private int happyLeft, sadLeft, wowLeft, blinkIn = 50, blinkLeft, idleFor, chirpIn = 260, playIn = 220;
	private boolean wasHurt;
	private int tpCd;
	private float sqWant = Float.NaN; // желаемое сжатие в этом тике (NaN — «дыхание»)
	private double ownerVyPrev;
	private Vec3 stayPos;
	private String lastMode = "follow";
	private final RandomSource rnd = RandomSource.create();

	public Slime(int ownerId, Vec3 start) {
		this.ownerId = ownerId;
		this.pos = this.prevPos = start;
	}

	// ───────── Команды из меню ─────────
	public void command(Pet.Act a) {
		this.perched = false;
		switch (a) {
			case PETTED -> begin(Act.PETTED, 80);
			case LOOP -> begin(Act.FLIP, 40);
			case ROAR -> begin(Act.WOW, 50);
			case SPIN -> begin(Act.DANCE, 96);
			case ROLL -> begin(Act.ROLL, 34);
			case VISIT -> begin(Act.VISIT, 70);
			case SULK -> begin(Act.SULK, 110);
			default -> { }
		}
	}

	private void begin(Act a, int len) {
		if (a != Act.PERCH) this.perched = false;
		this.act = a;
		this.actTicks = 0;
		this.actLen = len;
	}

	// ───────── Тик ─────────
	public void tick(Player owner, String mode, PetManager fx, boolean own) {
		this.prevPos = this.pos;
		this.yawO = this.yaw; this.pitchO = this.pitch; this.rollO = this.roll; this.sqO = this.sq;
		this.perchYO = this.perchY;
		this.age++;
		this.actTicks++;
		Level level = owner.level();

		Vec3 base = owner.position();
		float by = owner.yBodyRot * Mth.DEG_TO_RAD;
		Vec3 fwd = new Vec3(-Mth.sin(by), 0, Mth.cos(by));
		Vec3 left = new Vec3(Mth.cos(by), 0, Mth.sin(by));
		double mdx = owner.getX() - owner.xo, mdz = owner.getZ() - owner.zo, mdy = owner.getY() - owner.yo;
		boolean ownerMoving = mdx * mdx + mdz * mdz > 0.0009 || Math.abs(mdy) > 0.05 || !owner.onGround();
		this.idleFor = ownerMoving || owner.isSprinting() ? 0 : this.idleFor + 1;
		boolean staying = mode.equals("stay");

		// далеко отстал (телепорт, элитры, смена мира) — появляется рядом в облачке
		if (this.tpCd > 0) this.tpCd--;
		if (!staying && this.tpCd == 0 && this.pos.distanceToSqr(base) > (this.perched ? 9 : 14 * 14)) {
			this.tpCd = 20;
			fx.slimePoof(this.pos);
			Vec3 near = base.add(left.scale(0.9)).add(fwd.scale(-0.3));
			double gy = landY(level, near.x, near.z, base.y + 1.5, 4.0);
			this.pos = this.prevPos = new Vec3(near.x, Double.isNaN(gy) ? base.y : gy, near.z);
			this.hopping = false; this.falling = false;
			if (this.act != Act.PERCH) this.act = Act.IDLE;
			this.wowLeft = 20;
			fx.slimePoof(this.pos);
		}

		// смена режима
		if (!mode.equals(this.lastMode)) {
			this.lastMode = mode;
			if (mode.equals("stay")) { this.stayPos = null; begin(Act.STAY, 0); }
			else if (mode.equals("shoulder")) begin(Act.PERCH, 0);
			else if (this.act == Act.PERCH || this.act == Act.STAY) { if (this.act == Act.STAY) this.happyLeft = 50; begin(Act.IDLE, 0); }
		}
		boolean special = this.act == Act.PETTED || this.act == Act.FLIP || this.act == Act.WOW || this.act == Act.DANCE
			|| this.act == Act.ROLL || this.act == Act.VISIT || this.act == Act.SULK;
		boolean wet = owner.isInWater() || owner.isUnderWater() || owner.isSwimming();
		if (!special) {
			if (mode.equals("shoulder") && this.act != Act.PERCH && !wet) begin(Act.PERCH, 0);
			if (staying && this.act != Act.STAY) begin(Act.STAY, 0);
		}
		if (this.act == Act.PERCH && wet) { this.perched = false; begin(Act.IDLE, 0); } // хозяин нырнул — спрыгивает и плывёт рядом

		// хозяина ударили — пугается, подпрыгивает и встаёт перед ним
		boolean hurtNow = owner.hurtTime > 0;
		if (hurtNow && !this.wasHurt && mode.equals("follow") && owner.getHealth() > 0F && hitByCreature(owner, level) && !special) {
			this.perched = false;
			begin(Act.GUARD, 40);
			fx.slimeWow(this);
		}
		this.wasHurt = hurtNow;

		// ── Что делаем ──
		Vec3 goal = null;             // куда скакать (точка на земле)
		boolean faceOwner = true;
		float hopScale = 1F;
		this.spinYaw *= 0.8F;
		this.sqWant = Float.NaN;
		switch (this.act) {
			case PERCH -> {
				double th = owner.isShiftKeyDown() || owner.isCrouching() ? PERCH_CROUCH : PERCH_STAND;
				if (owner.isSleeping() || owner.isPassenger() && !owner.onGround()) th += 0.05;
				this.perchY += (th - this.perchY) * 0.55;
				Vec3 sh = Pet.shoulder(owner, 1F, this.perchY);
				if (this.perched) {
					this.pos = sh; this.hopping = false; this.falling = false;
					this.yaw = owner.yBodyRot;
					// хозяин прыгнул или приземлился — желе на плече пружинит
					double vy = owner.getY() - owner.yo;
					if (vy - this.ownerVyPrev > 0.25) this.sqV -= 0.25F;
					if (this.ownerVyPrev - vy > 0.25) this.sqV += 0.35F;
					this.ownerVyPrev = vy;
					if (this.idleFor > 900) this.face = SLEEP;
				} else if (!this.hopping) {
					// запрыгивает на плечо
					startHop(fx, this.pos, sh, Math.max(0.5, sh.y - this.pos.y + 0.35), 9, 0, 0);
				}
			}
			case STAY -> {
				if (this.stayPos == null) {
					double gy = Pet.groundBelow(level, this.pos.add(0, 0.3, 0), 64.0);
					this.stayPos = Double.isNaN(gy) ? this.pos : new Vec3(this.pos.x, gy, this.pos.z);
				}
				if (this.pos.distanceToSqr(this.stayPos) > 0.04) goal = this.stayPos;
				faceOwner = this.actTicks < 900;
				if (this.actTicks > 1300) this.face = SLEEP;           // долго ждёт — засыпает
				else if (this.actTicks > 700 && base.distanceToSqr(this.pos) > 64) this.sadLeft = Math.max(this.sadLeft, 5); // хозяин далеко — скучает
			}
			case PETTED -> {
				goal = base.add(fwd.scale(0.9));
				this.happyLeft = 60;
				if (this.actTicks > 10) {
					this.sqV += Mth.sin(this.actTicks * 0.9F) * 0.06F;     // мурчит и трясётся от удовольствия
					if (this.actTicks == 14) fx.slimePurr(this);
					if (this.actTicks % 10 == 0) fx.slimeHearts(this);
				}
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case FLIP -> {
				// сальто: большой прыжок на месте с переворотом вперёд
				this.happyLeft = 30;
				if (this.actTicks == 2 && !this.hopping) startHop(fx, this.pos, this.pos, 1.15, 16, 360F, 0F);
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case ROLL -> {
				this.happyLeft = 30;
				if (this.actTicks == 2 && !this.hopping) startHop(fx, this.pos, this.pos.add(left.scale(0.6)), 0.55, 14, 0F, 360F);
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case WOW -> {
				// удивление: подскакивает, вытягивается и смотрит круглыми глазами
				this.wowLeft = 8;
				if (this.actTicks == 2) { fx.slimeWow(this); if (!this.hopping) startHop(fx, this.pos, this.pos, 0.7, 11, 0F, 0F); }
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case DANCE -> {
				// танец: подпрыгивает в ритм, на каждом прыжке поворачивается и качается
				this.happyLeft = 20;
				if (!this.hopping && this.restLeft <= 0 && this.actTicks > 4 && this.actTicks < this.actLen - 10) {
					this.spinYaw = 90F;
					startHop(fx, this.pos, this.pos, 0.32, 8, 0F, 0F);
					fx.slimeNote(this);
				}
				faceOwner = false;
				this.roll += (Mth.sin(this.actTicks * 0.4F) * 14F - this.roll) * 0.3F;
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case VISIT -> {
				// подскакивает к лицу: прыгает перед хозяином и смотрит вверх, радуется
				goal = base.add(fwd.scale(1.0));
				if (this.actTicks > 18) this.happyLeft = 6;
				if (this.actTicks > 22 && this.actTicks % 16 == 0 && !this.hopping) startHop(fx, this.pos, this.pos, 0.55, 10, 0F, 0F);
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case SULK -> {
				// надулся: сплющился, отвернулся, слеза; потом мирится и радостно подпрыгивает
				faceOwner = false;
				if (this.actTicks < this.actLen - 20) {
					this.sadLeft = 4;
					this.sqWant = 0.18F;
					float away = Pet.yawTo(this.pos, owner.getEyePosition()) + 150F;
					this.yaw += Mth.clamp(Mth.wrapDegrees(away - this.yaw), -8F, 8F);
					if (this.actTicks == 12) fx.slimeSad(this);
				} else {
					faceOwner = true;
					this.happyLeft = 30;
					if (this.actTicks == this.actLen - 16 && !this.hopping) startHop(fx, this.pos, this.pos, 0.45, 9, 0F, 0F);
				}
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			case GUARD -> {
				// встаёт перед хозяином и смотрит наружу
				goal = base.add(fwd.scale(1.1));
				faceOwner = false;
				this.wowLeft = 4;
				this.yaw += Mth.clamp(Mth.wrapDegrees(owner.yBodyRot - this.yaw), -12F, 12F);
				if (this.actTicks >= this.actLen) begin(Act.IDLE, 0);
			}
			default -> { // IDLE — рядом с хозяином, слева и чуть сзади
				goal = base.add(left.scale(0.85)).add(fwd.scale(-0.35));
				if (owner.isSprinting()) { goal = base.add(left.scale(0.7)).add(fwd.scale(-0.9)); hopScale = 1.6F; }
				// мало здоровья — держится вплотную и грустит
				if (owner.getHealth() > 0F && owner.getHealth() <= 6F) { goal = base.add(left.scale(0.6)).add(fwd.scale(0.25)); this.sadLeft = Math.max(this.sadLeft, 4); }
				if (this.idleFor > 640) this.face = SLEEP; // хозяин долго стоит — дремлет
				// иногда играет сам
				if (!ownerMoving && --this.playIn <= 0) {
					this.playIn = 180 + this.rnd.nextInt(280);
					int r = this.rnd.nextInt(10);
					if (r < 3 && !this.hopping) startHop(fx, this.pos, this.pos, 0.35, 9, 0F, 0F);          // подпрыгнул
					else if (r < 4) begin(Act.FLIP, 40);
					else if (r < 5) begin(Act.DANCE, 60);
					else if (r < 7) this.happyLeft = 40;
					else this.spinYaw = (this.rnd.nextBoolean() ? 1 : -1) * 60F;                              // оглядывается
				}
			}
		}

		// ── Прыжки к цели ──
		if (goal != null && !this.hopping && !this.falling && this.act != Act.PERCH) {
			double gy = landY(level, goal.x, goal.z, goal.y + 1.6, 5.0);
			Vec3 g = new Vec3(goal.x, Double.isNaN(gy) ? this.pos.y : gy, goal.z);
			if (!Pet.solidAt(level, g.add(0, 0.2, 0))) {
				double dx = g.x - this.pos.x, dz = g.z - this.pos.z;
				double dist = Math.sqrt(dx * dx + dz * dz), dy = g.y - this.pos.y;
				if ((dist > 0.55 || Math.abs(dy) > 0.6) && this.restLeft <= 0) {
					double maxHop = (dist > 4 ? 2.4 : 1.15) * hopScale;
					double d = Math.min(dist, maxHop);
					Vec3 to = dist < 1e-4 ? g : new Vec3(this.pos.x + dx / dist * d, 0, this.pos.z + dz / dist * d);
					double ty = landY(level, to.x, to.z, this.pos.y + Math.max(0, dy) + 1.6, 5.0);
					if (d < dist && !Double.isNaN(ty)) to = new Vec3(to.x, ty, to.z); else to = new Vec3(to.x, g.y, to.z);
					int len = (int) Math.round(6 + d * (dist > 4 ? 2.2 : 3.2));
					startHop(fx, this.pos, to, 0.32 + 0.14 * d + Math.max(0, to.y - this.pos.y), len, 0F, 0F);
				}
			}
		}
		if (this.restLeft > 0) this.restLeft--;

		// ── Полёт прыжка ──
		if (this.crouchLeft > 0) {
			this.crouchLeft--;
			this.sqV += (0.28F - this.sq) * 0.45F; // присел перед прыжком
			if (this.crouchLeft == 0) { this.sqV -= 0.55F; fx.slimeHop(this); }
		} else if (this.hopping) {
			this.hopT++;
			float t = Mth.clamp(this.hopT / (float) this.hopLen, 0F, 1F);
			Vec3 h = this.hopFrom.lerp(this.hopTo, t);
			this.pos = h.add(0, 4 * this.hopH * t * (1 - t), 0);
			if (this.hopFlip != 0) this.pitch = this.hopFlip * smooth(t);
			if (this.hopRoll != 0) this.roll = this.hopRoll * smooth(t);
			// в полёте вытянут, к земле — снова круглый
			this.sqV += ((t < 0.5F ? -0.16F : -0.04F) - this.sq) * 0.25F;
			if (this.hopT >= this.hopLen) {
				this.hopping = false;
				this.pos = this.hopTo;
				if (this.hopFlip != 0) { this.pitch = this.pitchO = 0; }
				if (this.hopRoll != 0) { this.roll = this.rollO = 0; }
				this.hopFlip = 0; this.hopRoll = 0;
				this.sqV += (float) Math.min(0.75, 0.3 + this.hopH * 0.5);
				this.restLeft = this.act == Act.DANCE ? 3 : 2 + this.rnd.nextInt(4);
				fx.slimeLand(this, this.hopH > 0.6);
				if (this.act == Act.PERCH) {
					Vec3 sh = Pet.shoulder(owner, 1F, this.perchY);
					if (this.pos.distanceTo(sh) < 0.45) { this.perched = true; this.pos = sh; }
				}
			}
			this.yaw += Mth.clamp(Mth.wrapDegrees(Pet.yawTo(this.hopFrom, this.hopTo) - this.yaw), -18F, 18F) * (this.hopFrom.distanceToSqr(this.hopTo) > 0.01 ? 1F : 0F);
		} else if (!this.perched) {
			// стоит на земле: проверяем опору и воду
			Vec3 wpos = this.pos.add(0, 0.2, 0);
			if (!level.getFluidState(BlockPos.containing(wpos)).isEmpty()) {
				double top = Pet.waterTop(level, wpos);
				this.pos = new Vec3(this.pos.x, this.pos.y + (top - 0.12 + Mth.sin(this.age * 0.15F) * 0.03 - this.pos.y) * 0.3, this.pos.z); // плавает и покачивается
				this.falling = false;
			} else if (!this.falling && ((int) this.age & 3) == 0) {
				double gy = Pet.groundBelow(level, this.pos.add(0, 0.05, 0), 1.0);
				if (Double.isNaN(gy) || gy < this.pos.y - 0.05) { this.falling = true; this.fallVy = 0; }
			}
			if (this.falling) {
				this.fallVy = Math.max(this.fallVy - 0.08, -1.4);
				double ny = this.pos.y + this.fallVy;
				double gy = Pet.groundBelow(level, this.pos.add(0, 0.05, 0), 2.0 - this.fallVy);
				this.sqV += (-0.12F - this.sq) * 0.2F;
				if (!Double.isNaN(gy) && ny <= gy) {
					this.pos = new Vec3(this.pos.x, gy, this.pos.z);
					this.falling = false;
					this.sqV += 0.7F;
					this.wowLeft = 16;
					if (this.act == Act.STAY) this.stayPos = this.pos;
					fx.slimeLand(this, true);
				} else if (ny < level.getMinY()) {
					this.falling = false; // в пустоту не падает — вернётся к хозяину телепортом
					this.pos = new Vec3(this.pos.x, level.getMinY(), this.pos.z);
				} else {
					this.pos = new Vec3(this.pos.x, ny, this.pos.z);
				}
			}
		}

		// ── Поворот к хозяину (стоя) ──
		if (!this.hopping && faceOwner && this.act != Act.PERCH) {
			float want = Pet.yawTo(this.pos, owner.getEyePosition()) + this.spinYaw;
			this.yaw += Mth.clamp(Mth.wrapDegrees(want - this.yaw), -10F, 10F);
		}
		if (this.act == Act.DANCE && this.hopping) this.yaw += 90F / Math.max(1, this.hopLen);

		// ── Желе: пружина сжатия + «дыхание» ──
		float breath = this.face == SLEEP ? 0.12F + Mth.sin(this.age * 0.06F) * 0.04F : Mth.sin(this.age * 0.11F) * 0.035F;
		float want = Float.isNaN(this.sqWant) ? breath : this.sqWant + Mth.sin(this.age * 0.11F) * 0.02F;
		if (!this.hopping && this.crouchLeft == 0) this.sqV += (want - this.sq) * 0.32F;
		this.sqV *= 0.74F;
		this.sq = Mth.clamp(this.sq + this.sqV, -0.45F, 0.45F);
		if (!this.hopping && this.act != Act.DANCE && this.act != Act.ROLL) this.roll *= 0.8F;

		// ── Лицо ──
		boolean asleep = this.face == SLEEP && (this.act == Act.IDLE && this.idleFor > 640 || this.act == Act.STAY && this.actTicks > 1300 || this.act == Act.PERCH && this.idleFor > 900);
		if (this.happyLeft > 0) this.happyLeft--;
		if (this.sadLeft > 0) this.sadLeft--;
		if (this.wowLeft > 0) this.wowLeft--;
		int f;
		if (this.wowLeft > 0) f = WOW;
		else if (this.sadLeft > 0) f = SAD;
		else if (this.happyLeft > 0) f = HAPPY;
		else if (asleep) f = SLEEP;
		else f = NORMAL;
		if (f == NORMAL && --this.blinkIn <= 0) { this.blinkLeft = 3; this.blinkIn = 50 + this.rnd.nextInt(100); }
		if (this.blinkLeft > 0) { this.blinkLeft--; if (f == NORMAL) f = BLINK; }
		if (f != SLEEP && this.face == SLEEP && !asleep) { this.wowLeft = 10; f = WOW; } // проснулся — «ой!»
		this.face = this.forceFace >= 0 ? this.forceFace : f;

		// ── Звуки «жизни» ──
		if (this.face != SLEEP && --this.chirpIn <= 0) {
			this.chirpIn = 300 + this.rnd.nextInt(400);
			if (this.face == SAD) fx.slimeSad(this); else fx.slimeChirp(this);
		}
	}

	private void startHop(PetManager fx, Vec3 from, Vec3 to, double h, int len, float flip, float roll) {
		this.hopping = true;
		this.hopFrom = from;
		this.hopTo = to;
		this.hopH = h;
		this.hopT = 0;
		this.hopLen = Math.max(4, len);
		this.hopFlip = flip;
		this.hopRoll = roll;
		this.crouchLeft = 3; // сначала присесть
		this.perched = false;
		// приседание — до старта: позиция пока на месте
		this.pos = from;
	}

	/** Высота, на которую можно встать в точке (x, z): земля или поверхность воды (NaN — опоры нет) */
	static double landY(Level level, double x, double z, double fromY, double depth) {
		double gy = Pet.groundBelow(level, new Vec3(x, fromY, z), depth);
		if (Double.isNaN(gy)) return gy;
		BlockPos wp = BlockPos.containing(x, gy + 0.2, z);
		if (!level.getFluidState(wp).isEmpty()) return Pet.waterTop(level, Vec3.atCenterOf(wp)) - 0.12;
		return gy;
	}

	private static float smooth(float t) {
		t = Mth.clamp(t, 0F, 1F);
		return t * t * (3F - 2F * t);
	}

	/** Урон нанёс моб или другой игрок (а не падение, огонь и т. п.) */
	private static boolean hitByCreature(Player owner, Level level) {
		DamageSource src = owner.getLastDamageSource();
		Entity by = src == null ? null : src.getEntity();
		if (by != null) return by != owner && (by instanceof Mob || by instanceof Enemy || by instanceof Player);
		if (src != null) return false;
		for (Entity e : level.getEntities(owner, owner.getBoundingBox().inflate(4.5))) {
			if (e instanceof Enemy) return true;
			if (e instanceof Player && e.distanceToSqr(owner) < 9.0) return true;
		}
		return false;
	}
}
