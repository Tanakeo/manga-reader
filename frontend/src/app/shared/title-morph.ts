export interface MorphFrame {
  x: number; y: number; width: number; height: number; radius: number;
  coverX: number; coverY: number; coverWidth: number; coverHeight: number;
  titleX: number; titleY: number;
  content: number; shade: number; opacity: number; titleOpacity: number;
}

/** Independent critically damped springs retain their velocity when redirected. */
export class TitleMorph {
  private frame?: number;
  private time = 0;
  private values?: MorphFrame;
  private target?: MorphFrame;
  private readonly velocities = {} as MorphFrame;
  private finish?: () => void;

  constructor(private readonly window: Window, private readonly render: (value: MorphFrame) => void) {}

  start(value: MorphFrame) {
    this.stop();
    this.values = {...value};
    for (const key of Object.keys(value) as (keyof MorphFrame)[]) this.velocities[key] = 0;
    this.render(this.values);
  }

  to(target: MorphFrame, finish: () => void) {
    if (!this.values) this.start(target);
    this.target = target;
    this.finish = finish;
    if (this.frame !== undefined) return;
    this.time = this.window.performance.now();
    this.frame = this.window.requestAnimationFrame(this.tick);
  }

  snap(target: MorphFrame) {
    this.start(target);
  }

  stop() {
    if (this.frame !== undefined) this.window.cancelAnimationFrame(this.frame);
    this.frame = undefined;
    this.finish = undefined;
  }

  private readonly tick = (now: number) => {
    this.frame = undefined;
    if (!this.target || !this.values) return;
    const dt = Math.max(0, Math.min((now - this.time) / 1000, 1 / 30)) / 4;
    this.time = now;
    const keys = Object.keys(this.target) as (keyof MorphFrame)[];
    for (let step = 0; step < 4; step++) {
      for (const key of keys) {
        this.velocities[key] += ((this.target[key] - this.values[key]) * 400 - this.velocities[key] * 40) * dt;
        this.values[key] += this.velocities[key] * dt;
      }
    }
    const done = keys.every(key => {
      const small = key === 'content' || key === 'shade' || key.includes('Opacity') || key === 'opacity';
      return Math.abs(this.values![key] - this.target![key]) < (small ? .002 : .15)
        && Math.abs(this.velocities[key]) < (small ? .02 : .8);
    });
    if (done) this.values = {...this.target};
    this.render(this.values);
    if (done) {
      const finish = this.finish;
      this.finish = undefined;
      finish?.();
    } else this.frame = this.window.requestAnimationFrame(this.tick);
  };
}
