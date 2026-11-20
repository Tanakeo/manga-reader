import { Component, DestroyRef, DOCUMENT, effect, ElementRef, inject, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, PRIMARY_OUTLET, Router, RouterLink } from '@angular/router';
import { LucideCompass, LucideDynamicIcon, LucideLibraryBig, LucidePanelsTopLeft } from '@lucide/angular';
import { filter, map } from 'rxjs';

interface Edge {
  position: number;
  velocity: number;
  target: number;
}

@Component({
  selector: 'app-navigation-dock',
  imports: [RouterLink, LucideDynamicIcon],
  styleUrl: './navigation-dock.css',
  template: `
    <nav class="dock" aria-label="Разделы">
      <div class="track" #track>
        <span class="indicator" aria-hidden="true" [hidden]="!selection().section"></span>
        @for (item of items; track item.path) {
          <a [routerLink]="item.path" [attr.aria-label]="item.label" [attr.title]="item.label"
            [attr.aria-current]="selection().section === item.path ? selection().current : null">
            <svg [lucideIcon]="item.icon" [size]="20" aria-hidden="true"/>
          </a>
        }
        <div class="ink" aria-hidden="true" [hidden]="!selection().section">
          @for (item of items; track item.path) {
            <span><svg [lucideIcon]="item.icon" [size]="20"/></span>
          }
        </div>
      </div>
    </nav>
  `,
})
export class NavigationDock {
  protected readonly items = [
    {path: '/catalog', label: 'Каталог', icon: LucidePanelsTopLeft},
    {path: '/library', label: 'Библиотека', icon: LucideLibraryBig},
    {path: '/browse', label: 'Обзор', icon: LucideCompass},
  ];

  private readonly router = inject(Router);
  private readonly browser = inject(DOCUMENT).defaultView;
  private readonly reduceMotion = this.browser?.matchMedia('(prefers-reduced-motion: reduce)');
  private readonly track = viewChild<ElementRef<HTMLElement>>('track');
  protected readonly selection = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map(event => this.sectionFor(event.urlAfterRedirects)),
    ),
    {initialValue: this.sectionFor(this.router.url)},
  );

  private readonly left: Edge = {position: 0, velocity: 0, target: 0};
  private readonly right: Edge = {position: 1, velocity: 0, target: 1};
  private activeIndex = -1;
  private frameId?: number;
  private lastTime = 0;
  private movingRight = true;

  constructor() {
    effect(() => {
      const track = this.track()?.nativeElement;
      const index = this.items.findIndex(item => item.path === this.selection().section);
      if (track) this.setDestination(track, index);
    });

    this.reduceMotion?.addEventListener('change', this.onMotionPreference);
    inject(DestroyRef).onDestroy(() => {
      this.stop();
      this.reduceMotion?.removeEventListener('change', this.onMotionPreference);
    });
  }

  private sectionFor(url: string) {
    const segments = this.router.parseUrl(url).root.children[PRIMARY_OUTLET]?.segments ?? [];
    const first = segments[0]?.path;
    const direct = this.items.find(item => item.path === '/' + first);
    const section = direct?.path ?? (first === 'read' ? '/catalog' : first === 'downloads' ? '/library' : null);
    return {section, current: direct && segments.length === 1 ? 'page' : 'location'};
  }

  private setDestination(track: HTMLElement, index: number) {
    if (index === this.activeIndex) return;
    const previous = this.activeIndex;
    this.activeIndex = index;
    if (index < 0) {
      this.stop();
      return;
    }

    this.movingRight = index > previous;
    this.left.target = index;
    this.right.target = index + 1;
    if (previous < 0 || this.reduceMotion?.matches) {
      this.snap(track);
    } else if (this.frameId === undefined && this.browser) {
      this.lastTime = this.browser.performance.now();
      this.frameId = this.browser.requestAnimationFrame(this.tick);
    }
  }

  private readonly onMotionPreference = (event: MediaQueryListEvent) => {
    const track = this.track()?.nativeElement;
    if (event.matches && track) this.snap(track);
  };

  private readonly tick = (now: number) => {
    this.frameId = undefined;
    const track = this.track()?.nativeElement;
    if (!track || !this.browser) return;
    const dt = Math.max(0, Math.min((now - this.lastTime) / 1000, 1 / 30)) / 4;
    this.lastTime = now;

    const lead = this.movingRight ? this.right : this.left;
    const trail = this.movingRight ? this.left : this.right;
    for (let step = 0; step < 4; step++) {
      this.advance(lead, 640, 38, dt);
      this.advance(trail, 150, 16, dt);
    }

    if (this.settled(this.left) && this.settled(this.right)) {
      this.snap(track);
      return;
    }
    this.render(track);
    this.frameId = this.browser.requestAnimationFrame(this.tick);
  };

  private advance(edge: Edge, stiffness: number, damping: number, dt: number) {
    edge.velocity += ((edge.target - edge.position) * stiffness - edge.velocity * damping) * dt;
    edge.position += edge.velocity * dt;
  }

  private settled(edge: Edge) {
    return Math.abs(edge.position - edge.target) < .003 && Math.abs(edge.velocity) < .03;
  }

  private snap(track: HTMLElement) {
    this.stop();
    for (const edge of [this.left, this.right]) {
      edge.position = edge.target;
      edge.velocity = 0;
    }
    this.render(track);
  }

  private render(track: HTMLElement) {
    const left = Math.max(-.05, this.left.position);
    const right = Math.min(this.items.length + .05, this.right.position);
    const start = Math.min(left, right);
    const end = Math.max(left, right);
    const height = Math.max(.66, Math.min(1.1, 1 / Math.sqrt(end - start)));
    track.style.setProperty('--indicator-start', `${100 * start / this.items.length}%`);
    track.style.setProperty('--indicator-end', `${100 * (this.items.length - end) / this.items.length}%`);
    track.style.setProperty('--indicator-inset', `${50 * (1 - height)}%`);
  }

  private stop() {
    if (this.frameId === undefined) return;
    this.browser?.cancelAnimationFrame(this.frameId);
    this.frameId = undefined;
  }
}
