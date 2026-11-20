// Adapted from DesignBookmark Command button, MIT.
import { afterNextRender, Component, DestroyRef, DOCUMENT, effect, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core';
import { LucideDynamicIcon, LucideSearch, LucideX } from '@lucide/angular';

@Component({
  selector: 'app-command-search',
  imports: [LucideDynamicIcon],
  styleUrl: './command-search.css',
  host: {'(pointerdown)': 'press(true)', '(pointerup)': 'press(false)', '(pointerleave)': 'press(false)'},
  template: `
    <div class="command" [class.open]="open()" (focusout)="onBlur($event)">
      @if (open()) {
        <form class="command-stage" role="search" [attr.aria-label]="label()" (submit)="submit($event)">
          <svg [lucideIcon]="searchIcon" [size]="17" aria-hidden="true"/>
          <input #field type="search" [value]="draft()" (input)="edit($event)"
            [attr.aria-label]="label()" [placeholder]="placeholder()" maxlength="120"
            (keydown.escape)="escape($event)">
          @if (draft() || value()) {
            <button type="button" class="command-clear" aria-label="Сбросить поиск" (click)="clear()">
              <svg [lucideIcon]="closeIcon" [size]="16" aria-hidden="true"/>
            </button>
          }
          <kbd aria-hidden="true">Esc</kbd>
        </form>
      } @else {
        <button #trigger class="command-stage command-trigger" type="button" (click)="expand(true)"
          [attr.aria-keyshortcuts]="mac ? 'Meta+K' : 'Control+K'" aria-expanded="false" [attr.aria-label]="label()">
          <svg [lucideIcon]="searchIcon" [size]="17" aria-hidden="true"/>
          <span>{{ value() || caption() }}</span>
          <span class="command-keys" aria-hidden="true">
            <kbd [class.held]="modifierHeld()">{{ mac ? '⌘' : 'Ctrl' }}</kbd>
            <kbd [class.held]="keyHeld()">K</kbd>
          </span>
        </button>
      }
    </div>
  `,
})
export class CommandSearch {
  readonly value = input('');
  readonly label = input('Поиск');
  readonly caption = input('Найти тайтл');
  readonly placeholder = input('Название тайтла…');
  readonly search = output<string>();
  protected readonly open = signal(false);
  protected readonly draft = signal('');
  protected readonly modifierHeld = signal(false);
  protected readonly keyHeld = signal(false);
  protected readonly searchIcon = LucideSearch;
  protected readonly closeIcon = LucideX;
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly injector = inject(Injector);
  private readonly field = viewChild<ElementRef<HTMLInputElement>>('field');
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  private readonly reduced = this.window?.matchMedia('(prefers-reduced-motion: reduce)');
  protected readonly mac = /mac|iphone|ipad/i.test(this.window?.navigator.platform ?? '');
  // The reference uses bounce=30, giving open damping 34 - .3 * 20 = 28.
  private readonly width = {value: 200, target: 200, velocity: 0, stiffness: 300, damping: 28, mass: 1};
  private readonly scale = {value: 1, target: 1, velocity: 0, stiffness: 520, damping: 34, mass: .6};
  private readonly springs = [this.width, this.scale];
  private frame?: number;
  private time = 0;

  constructor() {
    effect(() => {
      this.draft.set(this.value());
      if (this.value()) this.expand(false);
    });
    const events = new AbortController();
    const options = {signal: events.signal};
    this.window?.addEventListener('keydown', this.onKeyDown, options);
    this.window?.addEventListener('keyup', this.onKeyUp, options);
    this.window?.addEventListener('pointerup', () => this.press(false), options);
    this.window?.addEventListener('blur', () => this.release(), options);
    this.window?.document.addEventListener('pointerdown', event => {
      if (!this.host.contains(event.target as Node)) this.collapse(false);
    }, options);
    this.reduced?.addEventListener('change', () => this.animate(), options);
    inject(DestroyRef).onDestroy(() => {
      events.abort();
      if (this.frame !== undefined) this.window?.cancelAnimationFrame(this.frame);
    });
  }

  protected expand(focus: boolean) {
    this.open.set(true);
    this.width.target = 320;
    this.animate();
    if (focus) afterNextRender(() => this.field()?.nativeElement.focus({preventScroll: true}), {injector: this.injector});
  }

  private collapse(focus: boolean) {
    if (!this.open()) return;
    this.open.set(false);
    this.draft.set(this.value());
    this.width.target = 200;
    this.scale.target = 1;
    this.animate();
    if (focus) afterNextRender(() => this.trigger()?.nativeElement.focus({preventScroll: true}), {injector: this.injector});
  }

  protected edit(event: Event) {
    this.draft.set((event.target as HTMLInputElement).value);
  }

  protected submit(event: Event) {
    event.preventDefault();
    this.search.emit(this.draft().trim());
  }

  protected clear() {
    this.draft.set('');
    this.search.emit('');
    this.field()?.nativeElement.focus();
  }

  protected escape(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    this.collapse(true);
  }

  protected onBlur(event: FocusEvent) {
    if (event.target === this.field()?.nativeElement && !this.draft() && !this.host.contains(event.relatedTarget as Node)) {
      this.collapse(false);
    }
  }

  protected press(down: boolean) {
    this.scale.target = down && !this.reduced?.matches ? .97 : 1;
    this.animate();
  }

  private readonly onKeyDown = (event: KeyboardEvent) => {
    if ((event.target as HTMLElement)?.closest?.('dialog[open]')) return;
    const mod = this.mac ? event.metaKey : event.ctrlKey;
    this.modifierHeld.set(mod);
    this.keyHeld.set(mod && event.code === 'KeyK');
    if (mod && (event.code === 'KeyK' || event.key.toLowerCase() === 'k') && !event.altKey && !event.shiftKey) {
      event.preventDefault();
      if (!event.repeat) this.expand(true);
      this.press(true);
    }
  };

  private readonly onKeyUp = (event: KeyboardEvent) => {
    this.modifierHeld.set(this.mac ? event.metaKey : event.ctrlKey);
    this.keyHeld.set(false);
    this.press(false);
  };

  private release() {
    this.modifierHeld.set(false);
    this.keyHeld.set(false);
    this.press(false);
  }

  private animate() {
    if (this.reduced?.matches) {
      if (this.frame !== undefined) this.window?.cancelAnimationFrame(this.frame);
      this.frame = undefined;
      for (const spring of this.springs) {
        spring.value = spring.target;
        spring.velocity = 0;
      }
      this.render();
      return;
    }
    if (this.frame !== undefined || !this.window) return;
    this.time = this.window.performance.now();
    this.frame = this.window.requestAnimationFrame(this.tick);
  }

  private readonly tick = (now: number) => {
    this.frame = undefined;
    const dt = Math.max(0, Math.min((now - this.time) / 1000, 1 / 30)) / 4;
    this.time = now;
    for (let step = 0; step < 4; step++) {
      for (const spring of this.springs) {
        spring.velocity += ((spring.target - spring.value) * spring.stiffness - spring.velocity * spring.damping) / spring.mass * dt;
        spring.value += spring.velocity * dt;
      }
    }
    const done = this.springs.every(spring => Math.abs(spring.target - spring.value) < .01 && Math.abs(spring.velocity) < .02);
    if (done) {
      for (const spring of this.springs) {
        spring.value = spring.target;
        spring.velocity = 0;
      }
    }
    this.render();
    if (!done) this.frame = this.window?.requestAnimationFrame(this.tick);
  };

  private render() {
    this.host.style.setProperty('--command-width', this.width.value + 'px');
    this.host.style.setProperty('--command-scale', String(this.scale.value));
  }
}
