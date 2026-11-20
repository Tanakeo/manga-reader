import { afterNextRender, booleanAttribute, DestroyRef, Directive, DOCUMENT, effect, ElementRef, inject, input } from '@angular/core';

interface Spring {
  value: number;
  target: number;
  velocity: number;
  stiffness: number;
  damping: number;
}

const spring = (value: number, stiffness: number, damping: number): Spring =>
  ({value, target: value, velocity: 0, stiffness, damping});

@Directive({
  selector: 'a[magneticButton],button[magneticButton]',
  host: {
    '[class.magnetic-button]': 'true',
    '[attr.data-magnetic]': 'magneticButton()',
  },
})
export class MagneticButton {
  readonly magneticButton = input(true, {transform: booleanAttribute});
  readonly magneticRadius = input(24);
  readonly magneticStrength = input(6);
  readonly magneticDepth = input(.5);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly pointer = this.window?.matchMedia('(any-hover: hover) and (any-pointer: fine)');
  private readonly reduced = this.window?.matchMedia('(prefers-reduced-motion: reduce)');
  private readonly x = spring(0, 170, 13);
  private readonly y = spring(0, 170, 13);
  private readonly labelX = spring(0, 220, 15);
  private readonly labelY = spring(0, 220, 15);
  private readonly scale = spring(1, 400, 18);
  private readonly springs = [this.x, this.y, this.labelX, this.labelY, this.scale];
  private frame?: number;
  private time = 0;
  private pressed = false;
  private destroyed = false;

  constructor() {
    effect(() => {
      if (!this.magneticButton()) this.reset();
    });
    afterNextRender(() => {
      const window = this.window;
      if (!window) return;
      const events = new AbortController();
      const options = {signal: events.signal, passive: true};
      window.addEventListener('pointermove', this.move, options);
      window.addEventListener('pointerup', this.release, options);
      window.addEventListener('pointercancel', this.cancel, options);
      window.addEventListener('blur', this.cancel, options);
      window.addEventListener('scroll', this.cancel, {...options, capture: true});
      window.addEventListener('resize', this.cancel, options);
      window.document.addEventListener('pointerleave', this.cancel, options);
      window.document.addEventListener('visibilitychange', this.cancel, options);
      this.host.addEventListener('pointerdown', this.press, options);
      this.host.addEventListener('focusin', () => {
        if (this.host.matches(':focus-visible')) this.reset();
      }, options);
      this.pointer?.addEventListener('change', this.cancel, options);
      this.reduced?.addEventListener('change', this.cancel, options);
      const state = new MutationObserver(() => {
        if (this.unavailable()) this.reset();
      });
      state.observe(this.host, {attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'aria-busy']});
      this.cleanup = () => {
        events.abort();
        state.disconnect();
      };
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.cleanup?.();
      this.reset();
    });
  }

  private cleanup?: () => void;

  private unavailable() {
    return !this.magneticButton() || this.host.matches(':disabled,[aria-disabled="true"],[aria-busy="true"]');
  }

  private get enabled() {
    return !this.destroyed && !this.unavailable() && !!this.pointer?.matches && !this.reduced?.matches;
  }

  private readonly move = (event: PointerEvent) => {
    if (!this.enabled || event.pointerType !== 'mouse' || this.host.matches(':focus-visible')) {
      this.reset();
      return;
    }
    // The semantic control never transforms, so this rectangle stays stable during motion.
    const rect = this.host.getBoundingClientRect();
    const outerX = Math.max(rect.left - event.clientX, 0, event.clientX - rect.right);
    const outerY = Math.max(rect.top - event.clientY, 0, event.clientY - rect.bottom);
    const radius = this.magneticRadius();
    const distance = Math.hypot(outerX, outerY);
    if (!rect.width || !rect.height || distance >= radius) {
      this.cancel();
      return;
    }
    const influence = 1 - distance / radius;
    const dx = Math.max(-1, Math.min(1, (event.clientX - rect.left - rect.width / 2) / (rect.width / 2)));
    const dy = Math.max(-1, Math.min(1, (event.clientY - rect.top - rect.height / 2) / (rect.height / 2)));
    this.x.target = dx * this.magneticStrength() * influence;
    this.y.target = dy * this.magneticStrength() * influence;
    this.labelX.target = this.x.target * this.magneticDepth();
    this.labelY.target = this.y.target * this.magneticDepth();
    this.scale.target = this.pressed ? .96 : 1;
    this.start();
  };

  private readonly press = (event: PointerEvent) => {
    if (!this.enabled || event.pointerType !== 'mouse') {
      this.reset();
      return;
    }
    if (event.button !== 0) return;
    this.pressed = true;
    this.scale.target = .96;
    this.start();
  };

  private readonly release = () => {
    if (!this.pressed) return;
    this.pressed = false;
    this.scale.target = 1;
    if (this.enabled) this.start();
    else this.reset();
  };

  private readonly cancel = () => {
    this.pressed = false;
    if (!this.enabled || this.host.matches(':focus-visible')) {
      this.reset();
      return;
    }
    for (const item of this.springs) item.target = item === this.scale ? 1 : 0;
    if (this.springs.some(item => !this.settled(item))) this.start();
  };

  private start() {
    if (this.frame !== undefined || !this.window || this.destroyed) return;
    this.time = this.window.performance.now();
    this.frame = this.window.requestAnimationFrame(this.tick);
  }

  private readonly tick = (time: number) => {
    this.frame = undefined;
    if (!this.enabled) {
      this.reset();
      return;
    }
    const dt = Math.max(0, Math.min((time - this.time) / 1000, 1 / 30)) / 4;
    this.time = time;
    for (let step = 0; step < 4; step++) {
      for (const item of this.springs) {
        item.velocity += ((item.target - item.value) * item.stiffness - item.velocity * item.damping) * dt;
        item.value += item.velocity * dt;
      }
    }
    const settled = this.springs.every(item => this.settled(item));
    if (settled) {
      for (const item of this.springs) {
        item.value = item.target;
        item.velocity = 0;
      }
    }
    this.render();
    if (!settled) this.frame = this.window?.requestAnimationFrame(this.tick);
  };

  private settled(item: Spring) {
    return Math.abs(item.target - item.value) < .001 && Math.abs(item.velocity) < .01;
  }

  private render() {
    this.host.style.setProperty('--mag-x', this.x.value.toFixed(3) + 'px');
    this.host.style.setProperty('--mag-y', this.y.value.toFixed(3) + 'px');
    this.host.style.setProperty('--mag-label-x', this.labelX.value.toFixed(3) + 'px');
    this.host.style.setProperty('--mag-label-y', this.labelY.value.toFixed(3) + 'px');
    this.host.style.setProperty('--mag-scale', this.scale.value.toFixed(4));
  }

  private reset() {
    if (this.frame === undefined && !this.pressed && this.springs.every(item =>
      item.value === (item === this.scale ? 1 : 0) && item.target === item.value && item.velocity === 0)) return;
    if (this.frame !== undefined) this.window?.cancelAnimationFrame(this.frame);
    this.frame = undefined;
    this.pressed = false;
    for (const item of this.springs) {
      item.value = item.target = item === this.scale ? 1 : 0;
      item.velocity = 0;
    }
    this.render();
  }
}
