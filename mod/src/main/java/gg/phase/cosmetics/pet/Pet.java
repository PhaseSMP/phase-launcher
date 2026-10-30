package gg.phase.cosmetics.pet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Живой дракончик одного игрока. Всё считается на клиенте каждый тик (20 раз в секунду):
 * куда лететь, что делать (кружить, играть, садиться, спать, сидеть на плече), как двигать крыльями,
 * хвостом и головой. Все параметры позы меняются плавно, поэтому переходы между позами мягкие.
 */
public final class Pet {
	// ── Параметры позы (плавно стремятся к цели) ──
	public static final int FLY = 0, SIT = 1, LIE = 2, FOLD = 3, HEAD_YAW = 4, HEAD_PITCH = 5, JAW = 6, FLAP_AMP = 7,
		CURL = 8, PERCH = 9, WAG = 10, LEAN = 11, N = 12;
	private static final float[] RATE = { 0.12F, 0.1F, 0.06F, 0.14F, 0.2F, 0.2F, 0.3F, 0.15F, 0.06F, 0.2F, 0.1F, 0.15F };

	public enum Act { HOVER, LOOP, CIRCLE, ROLL, DIVE, LAND, GROUND, TAKEOFF, PERCH, STAY, PETTED, ROAR, SPIN, VISIT }

	public final int ownerId;
	public Vec3 pos, prevPos;
	private Vec3 vel = Vec3.ZERO;
	public float yaw, yawO, pitch, pitchO, roll, rollO;
	public final float[] p = new float[N], o = new float[N];
	private final float[] target = new float[N];
	public float flap, flapO, tail, tailO, age;
	public int head; // 0 открыты, 1 моргает, 2 счастлив, 3 спит
	public boolean hidden;

	public Act act = Act.HOVER;
	private int actTicks, actLen, idleFor, blinkIn = 40, blinkLeft, chirpIn = 200, snoreIn = 60, playIn = 200, flapSoundIn;
	private int happyLeft;
	private Vec3 stayPos, landAt;
	private float lookYaw, lookPitch, lookIn;
	private final RandomSource rnd = RandomSource.create();
	private String lastMode = "follow";

	public Pet(int ownerId, Vec3 start) {
		this.ownerId = ownerId;
		this.pos = this.prevPos = start;
		this.p[FLY] = this.o[FLY] = this.target[FLY] = 1F;
	}

	// ───────── Команды ─────────
	public void command(Act a) {
		switch (a) {
			case PETTED -> begin(Act.PETTED, 70);
			case LOOP -> begin(Act.LOOP, 36);
			case ROAR -> begin(Act.ROAR, 46);
			case SPIN -> begin(Act.SPIN, 80);
			case ROLL -> begin(Act.ROLL, 24);
			default -> { }
		}
	}

	private void begin(Act a, int len) {
		if (this.act == Act.LOOP && a != Act.LOOP) this.finishLoop();
		this.act = a;
		this.actTicks = 0;
		this.actLen = len;
	}

	private void finishLoop() {
		// петля накрутила −360° по тангажу — убираем без скачка
		while (this.pitch < -180F) { this.pitch += 360F; this.pitchO += 360F; }
	}

	// ───────── Тик ─────────
	public void tick(Player owner, String mode, PetFx fx, boolean own) {
		this.prevPos = this.pos;
		this.yawO = this.yaw; this.pitchO = this.pitch; this.rollO = this.roll;
		System.arraycopy(this.p, 0, this.o, 0, N);
		this.flapO = this.flap; this.tailO = this.tail;
		this.age++;
		this.actTicks++;
		Level level = owner.level();

		Vec3 base = owner.position();
		float by = owner.yBodyRot * Mth.DEG_TO_RAD;
		Vec3 fwd = new Vec3(-Mth.sin(by), 0, Mth.cos(by));
		Vec3 left = new Vec3(Mth.cos(by), 0, Mth.sin(by));
		boolean ownerMoving = owner.getDeltaMovement().horizontalDistanceSqr() > 0.0009 || !owner.onGround();
		this.idleFor = ownerMoving || owner.isSprinting() ? 0 : this.idleFor + 1;

		// далеко отстал (телепорт игрока, смена мира, полёт на элитрах) — появляется рядом
		if (this.pos.distanceToSqr(base) > 24 * 24) {
			fx.teleport(this, this.pos);
			this.pos = this.prevPos = base.add(left.scale(0.9)).add(0, 1.6, 0);
			this.vel = Vec3.ZERO;
			this.act = Act.HOVER;
			fx.teleport(this, this.pos);
		}

		// смена режима
		if (!mode.equals(this.lastMode)) {
			this.lastMode = mode;
			if (mode.equals("stay")) { this.stayPos = this.pos; begin(Act.STAY, 0); }
			else if (mode.equals("shoulder")) begin(Act.PERCH, 0);
			else if (this.act == Act.PERCH || this.act == Act.STAY) begin(Act.TAKEOFF, 12);
		}
		boolean special = this.act == Act.PETTED || this.act == Act.LOOP || this.act == Act.ROAR || this.act == Act.SPIN || this.act == Act.ROLL;
		if (!special) {
			if (mode.equals("shoulder") && this.act != Act.PERCH) begin(Act.PERCH, 0);
			if (mode.equals("stay") && this.act != Act.STAY) { if (this.stayPos == null) this.stayPos = this.pos; begin(Act.STAY, 0); }
		}

		Vec3 goal;               // куда лететь (точка «под брюшком»)
		float stiff = 0.05F, maxSpeed = 0.55F;
		boolean grounded = false; // стоит/сидит/лежит на чём-то
		float wantSit = 0, wantLie = 0, wantPerch = 0;
		float faceYaw = Float.NaN, wantPitch = 0, wantRoll = Float.NaN;
		Vec3 lookAt = owner.getEyePosition();
		float flapAmp = 1F, flapSpeed = 0.55F;
		this.head = 0;

		Vec3 hover = base.add(left.scale(0.85)).add(fwd.scale(-0.25)).add(0, owner.isShiftKeyDown() ? 1.35 : 1.6, 0)
			.add(Mth.sin(this.age * 0.031F) * 0.3, Mth.sin(this.age * 0.047F) * 0.18, Mth.cos(this.age * 0.027F) * 0.3);
		if (owner.isSprinting() || owner.isFallFlying()) hover = base.add(fwd.scale(-1.0)).add(left.scale(0.7)).add(0, 1.4, 0);
		if (owner.isFallFlying()) { maxSpeed = 3.5F; stiff = 0.2F; }

		switch (this.act) {
			case PERCH -> {
				// левое плечо
				Vec3 sh = base.add(left.scale(0.37)).add(fwd.scale(0.02)).add(0, owner.isShiftKeyDown() ? 1.2 : 1.42, 0);
				if (owner.isSleeping() || owner.isPassenger() && !owner.onGround()) sh = sh.add(0, 0.05, 0);
				double d = this.pos.distanceTo(sh);
				goal = sh;
				if (d < 0.35) {
					grounded = true; wantPerch = 1; wantSit = 1;
					this.pos = sh; this.vel = Vec3.ZERO;
					faceYaw = owner.yBodyRot;
					lookAt = owner.getEyePosition().add(owner.getViewVector(1F).scale(6));
					if (this.idleFor > 900) { wantLie = 0.6F; this.head = 3; }
				} else { stiff = 0.12F; maxSpeed = 0.7F; }
			}
			case STAY -> {
				if (this.stayPos == null) this.stayPos = this.pos;
				double gy = groundBelow(level, this.stayPos, 3.0);
				goal = new Vec3(this.stayPos.x, Double.isNaN(gy) ? this.stayPos.y : gy, this.stayPos.z);
				if (this.pos.distanceToSqr(goal) < 0.04) {
					grounded = true; this.pos = goal; this.vel = Vec3.ZERO;
					wantSit = this.idleFor > 400 || this.actTicks > 600 ? 0.4F : 1F;
					wantLie = this.actTicks > 600 ? 1F : 0F;
					if (wantLie > 0) this.head = 3;
					faceYaw = yawTo(this.pos, base);
				} else { stiff = 0.08F; maxSpeed = 0.35F; }
				if (base.distanceToSqr(this.pos) > 20 * 20) { this.stayPos = null; begin(Act.HOVER, 0); this.lastMode = "follow"; }
			}
			case LAND -> {
				if (this.landAt == null || ownerMoving) { begin(Act.HOVER, 0); goal = hover; break; }
				goal = this.landAt;
				maxSpeed = 0.18F; stiff = 0.07F;
				if (this.pos.distanceToSqr(this.landAt) < 0.01) { begin(Act.GROUND, 0); fx.land(this); }
				flapAmp = 0.8F; flapSpeed = 0.7F;
			}
			case GROUND -> {
				goal = this.landAt == null ? this.pos : this.landAt;
				grounded = true; this.pos = goal; this.vel = Vec3.ZERO;
				wantSit = 1F;
				if (this.idleFor > 600) { wantLie = 1F; this.head = 3; }
				faceYaw = this.idleFor > 600 ? this.yaw : yawTo(this.pos, base) + 25F;
				// игрок ушёл или прыгнул — взлетаем
				if (ownerMoving && (this.pos.distanceToSqr(base) > 2.0 || !owner.onGround() || owner.isSprinting())) {
					begin(Act.TAKEOFF, 12);
					fx.takeoff(this);
				}
			}
			case TAKEOFF -> {
				goal = this.pos.add(0, 0.5, 0);
				stiff = 0.2F; flapAmp = 1.3F; flapSpeed = 0.9F;
				if (this.actTicks >= this.actLen) begin(Act.HOVER, 0);
			}
			case LOOP -> {
				// петля на месте: тангаж проходит −360°
				goal = hover.add(0, 0.6, 0);
				float t = this.actTicks / (float) this.actLen;
				wantPitch = Float.NaN;
				this.pitch = -360F * smooth(t);
				flapAmp = 1.4F; flapSpeed = 0.95F;
				this.happyLeft = 20;
				if (this.actTicks >= this.actLen) { this.finishLoop(); this.pitch = 0; begin(Act.HOVER, 0); }
			}
			case ROLL -> {
				goal = hover;
				wantRoll = Float.NaN;
				this.roll = 360F * smooth(this.actTicks / (float) this.actLen);
				if (this.actTicks >= this.actLen) { this.roll = this.rollO = 0; begin(Act.HOVER, 0); }
			}
			case SPIN -> {
				// круги вокруг игрока с креном
				float a = this.actTicks * 0.16F + by;
				goal = base.add(Mth.cos(a) * 1.6, 1.7 + Mth.sin(this.actTicks * 0.2F) * 0.25, Mth.sin(a) * 1.6);
				stiff = 0.16F; maxSpeed = 0.6F; flapAmp = 1.2F; flapSpeed = 0.8F;
				wantRoll = -38F;
				if (this.actTicks >= this.actLen) begin(Act.HOVER, 0);
			}
			case CIRCLE -> {
				float a = this.actTicks * 0.09F + by;
				goal = base.add(Mth.cos(a) * 1.3, 1.9, Mth.sin(a) * 1.3);
				stiff = 0.1F; maxSpeed = 0.4F;
				wantRoll = -25F;
				if (this.actTicks >= this.actLen || ownerMoving) begin(Act.HOVER, 0);
			}
			case DIVE -> {
				float t = this.actTicks / (float) this.actLen;
				goal = base.add(fwd.scale(1.3 - t * 0.6)).add(left.scale(0.6 - t * 1.4)).add(0, 1.9 - Mth.sin(t * Mth.PI) * 1.3, 0);
				stiff = 0.14F; maxSpeed = 0.5F;
				if (this.actTicks >= this.actLen || ownerMoving) begin(Act.HOVER, 0);
			}
			case VISIT -> {
				// подлетает к лицу и смотрит на игрока
				goal = owner.getEyePosition().add(owner.getViewVector(1F).scale(1.1)).add(0, -0.25, 0);
				stiff = 0.1F; maxSpeed = 0.35F;
				faceYaw = yawTo(this.pos, owner.getEyePosition());
				if (this.actTicks > 30) this.head = 2;
				if (this.actTicks >= this.actLen || ownerMoving) begin(Act.HOVER, 0);
			}
			case PETTED -> {
				goal = owner.getEyePosition().add(owner.getViewVector(1F).scale(0.95)).add(0, -0.3, 0);
				stiff = 0.12F; maxSpeed = 0.4F;
				faceYaw = yawTo(this.pos, owner.getEyePosition());
				wantRoll = Mth.sin(this.actTicks * 0.45F) * 14F;
				this.head = 2;
				this.happyLeft = 60;
				if (this.actTicks == 12) fx.purr(this);
				if (this.actTicks % 9 == 0 && this.actTicks > 8) fx.hearts(this);
				if (this.actTicks >= this.actLen) begin(Act.HOVER, 0);
			}
			case ROAR -> {
				goal = hover;
				faceYaw = owner.getYRot();
				lookAt = owner.getEyePosition().add(owner.getViewVector(1F).scale(8));
				float t = this.actTicks;
				this.target[JAW] = t > 8 && t < 36 ? 1F : 0F;
				if (this.actTicks == 9) fx.roar(this);
				if (t > 10 && t < 34 && this.actTicks % 2 == 0) fx.breath(this, owner.getViewVector(1F));
				if (this.actTicks >= this.actLen) begin(Act.HOVER, 0);
			}
			default -> { // HOVER
				goal = hover;
				// игрок отдыхает на земле — садимся рядом
				if (this.idleFor > 70 && owner.onGround() && !owner.isInWater()) {
					Vec3 spot = base.add(left.scale(0.85)).add(fwd.scale(0.55));
					double gy = groundBelow(level, spot.add(0, 1.0, 0), 3.0);
					if (!Double.isNaN(gy) && Math.abs(gy - base.y) < 1.1) { this.landAt = new Vec3(spot.x, gy, spot.z); begin(Act.LAND, 0); }
				}
				// иногда играет сам
				if (--this.playIn <= 0) {
					this.playIn = 160 + this.rnd.nextInt(260);
					int r = this.rnd.nextInt(10);
					if (r < 3) begin(Act.CIRCLE, 70 + this.rnd.nextInt(50));
					else if (r < 5) begin(Act.LOOP, 36);
					else if (r < 7) begin(Act.DIVE, 40);
					else if (r < 8) begin(Act.ROLL, 24);
					else if (r < 9 && !ownerMoving) begin(Act.VISIT, 60);
					else this.happyLeft = 30;
				}
			}
		}

		// ── Движение ──
		if (!grounded) {
			Vec3 to = goal.subtract(this.pos);
			Vec3 want = to.scale(stiff * 6F);
			double ws = want.length();
			if (ws > maxSpeed) want = want.scale(maxSpeed / ws);
			this.vel = this.vel.add(want.subtract(this.vel).scale(0.22));
			Vec3 next = this.pos.add(this.vel);
			// не залетать в блоки: поднимаемся над препятствием
			if (solidAt(level, next.add(0, 0.12, 0))) {
				next = new Vec3(next.x, Math.max(next.y, Math.floor(next.y + 0.12) + 1.02), next.z);
				this.vel = new Vec3(this.vel.x * 0.5, Math.max(this.vel.y, 0.05), this.vel.z * 0.5);
			}
			this.pos = next;
		}

		// ── Поза ──
		float speed = (float) this.vel.horizontalDistance();
		boolean landing = this.act == Act.LAND && this.pos.distanceToSqr(goal) < 0.09;
		this.target[FLY] = grounded ? 0F : landing ? 0.4F : 1F;
		this.target[FOLD] = grounded ? 1F : landing ? 0.3F : 0F;
		this.target[SIT] = grounded ? Math.max(0, wantSit - wantLie) : 0F;
		this.target[LIE] = grounded ? wantLie : 0F;
		this.target[PERCH] = wantPerch;
		this.target[FLAP_AMP] = grounded ? 0F : flapAmp * (0.75F + Math.min(speed * 1.5F, 0.6F));
		if (this.act != Act.ROAR) this.target[JAW] = this.head == 3 ? 0F : this.target[JAW] * 0.5F;
		if (this.happyLeft > 0) { this.happyLeft--; this.head = this.head == 0 ? 2 : this.head; }
		this.target[WAG] = this.happyLeft > 0 || this.act == Act.PETTED ? 1F : grounded && this.head != 3 ? 0.25F : 0.4F;
		this.target[CURL] = grounded ? 1F : 0F;

		// поворот: по скорости в полёте, иначе — к цели взгляда
		if (Float.isNaN(faceYaw)) faceYaw = speed > 0.06 ? (float) (Mth.atan2(-this.vel.x, this.vel.z) * Mth.RAD_TO_DEG) : yawTo(this.pos, lookAt);
		float dy = Mth.wrapDegrees(faceYaw - this.yaw);
		float turn = Mth.clamp(dy, -14F, 14F) * (grounded ? 0.5F : 1F);
		this.yaw += turn;
		if (!Float.isNaN(wantPitch)) {
			wantPitch = grounded ? 0F : Mth.clamp((float) -this.vel.y * 160F, -35F, 35F);
			this.pitch += (wantPitch - this.pitch) * 0.2F;
		}
		if (!Float.isNaN(wantRoll)) {
			float wr = Float.isNaN(wantRoll) ? 0 : wantRoll;
			if (this.act != Act.SPIN && this.act != Act.CIRCLE && this.act != Act.PETTED) wr = grounded ? 0F : Mth.clamp(-turn * 3.2F, -40F, 40F);
			this.roll += (wr - this.roll) * 0.15F;
		} else if (this.act != Act.ROLL) {
			this.roll += (0 - this.roll) * 0.15F;
		}

		// голова смотрит на цель (+ иногда оглядывается)
		if (--this.lookIn <= 0) {
			this.lookIn = 40 + this.rnd.nextInt(80);
			boolean around = this.rnd.nextInt(3) == 0;
			this.lookYaw = around ? (this.rnd.nextFloat() - 0.5F) * 120F : 0F;
			this.lookPitch = around ? (this.rnd.nextFloat() - 0.5F) * 40F : 0F;
		}
		Vec3 eye = this.pos.add(0, 0.35, 0);
		float ly = Mth.wrapDegrees(yawTo(eye, lookAt) - this.yaw + this.lookYaw);
		double hd = Math.sqrt((lookAt.x - eye.x) * (lookAt.x - eye.x) + (lookAt.z - eye.z) * (lookAt.z - eye.z));
		float lp = (float) (Mth.atan2(lookAt.y - eye.y, hd) * Mth.RAD_TO_DEG) + this.lookPitch;
		this.target[HEAD_YAW] = this.head == 3 ? 0F : Mth.clamp(ly, -75F, 75F);
		this.target[HEAD_PITCH] = this.head == 3 ? -10F : Mth.clamp(lp - this.pitch * 0.5F, -40F, 45F);
		this.target[LEAN] = Mth.clamp((float) this.vel.y * 6F, -1F, 1F);

		for (int i = 0; i < N; i++) this.p[i] += (this.target[i] - this.p[i]) * RATE[i];

		// крылья и хвост
		float fs = grounded ? 0.0F : flapSpeed * (0.8F + Math.min(speed * 1.2F, 0.7F));
		this.flap += fs;
		this.tail += 0.08F + this.p[WAG] * 0.22F;
		if (!grounded && this.p[FLAP_AMP] > 0.4F && --this.flapSoundIn <= 0) {
			this.flapSoundIn = Math.max(8, (int) (Mth.TWO_PI / Math.max(fs, 0.05F)) * 2);
			fx.flap(this);
		}

		// моргание
		if (this.head == 0 && --this.blinkIn <= 0) { this.blinkLeft = 3; this.blinkIn = 50 + this.rnd.nextInt(90); }
		if (this.blinkLeft > 0) { this.blinkLeft--; if (this.head == 0) this.head = 1; }

		// звуки «жизни»
		if (this.head == 3) {
			if (--this.snoreIn <= 0) { this.snoreIn = 70 + this.rnd.nextInt(40); fx.snore(this); }
		} else if (--this.chirpIn <= 0) {
			this.chirpIn = 240 + this.rnd.nextInt(360);
			fx.chirp(this);
		}
		this.hidden = false;
	}

	// ───────── Вспомогательное ─────────
	private static float smooth(float t) {
		t = Mth.clamp(t, 0F, 1F);
		return t * t * (3F - 2F * t);
	}

	static float yawTo(Vec3 from, Vec3 to) {
		return (float) (Mth.atan2(-(to.x - from.x), to.z - from.z) * Mth.RAD_TO_DEG);
	}

	static boolean solidAt(Level level, Vec3 v) {
		BlockPos bp = BlockPos.containing(v);
		BlockState st = level.getBlockState(bp);
		if (st.isAir()) return false;
		VoxelShape sh = st.getCollisionShape(level, bp);
		if (sh.isEmpty()) return false;
		double fy = v.y - bp.getY();
		return fy <= sh.max(Direction.Axis.Y) && fy >= sh.min(Direction.Axis.Y);
	}

	/** Высота поверхности под точкой (или NaN, если опоры нет в пределах depth блоков) */
	static double groundBelow(Level level, Vec3 v, double depth) {
		BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
		int top = Mth.floor(v.y), bottom = Mth.floor(v.y - depth);
		for (int y = top; y >= bottom; y--) {
			bp.set(Mth.floor(v.x), y, Mth.floor(v.z));
			BlockState st = level.getBlockState(bp);
			if (st.isAir()) continue;
			VoxelShape sh = st.getCollisionShape(level, bp);
			if (sh.isEmpty()) continue;
			double gy = y + sh.max(Direction.Axis.Y);
			if (gy <= v.y + 0.01) return gy;
		}
		return Double.NaN;
	}
}
