import { afterNextRender, Component, computed, DestroyRef, DOCUMENT, effect, ElementRef, inject, Injector, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { LucideDynamicIcon, LucideX, LucideArrowUpRight } from '@lucide/angular';
import { Cover } from '../common';
import { CatalogFacts } from '../catalog-facts';
import { LocalizedTitle, TitleLocale } from '../title-locale';
import { problem } from '../api';
import { TitleDetails } from './title-details';
import { findPreviewTrigger, PreviewSelection, TitlePreviewState } from './title-preview-state';
import { MorphFrame, TitleMorph } from './title-morph';

@Component({
  selector: 'app-title-preview',
  imports: [Cover, CatalogFacts, LocalizedTitle, LucideDynamicIcon],
  templateUrl: './title-preview.html',
  styleUrl: './title-preview.css',
})
export class TitlePreview {
  protected readonly state = inject(TitlePreviewState);
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView!;
  private readonly injector = inject(Injector);
  private readonly details = inject(TitleDetails);
  protected readonly language = inject(TitleLocale).language;
  protected readonly displayed = signal<PreviewSelection | undefined>(undefined);
  protected readonly moving = signal(true);
  protected readonly detail = rxResource({params: () => this.displayed()?.key, stream: ({params}) => this.details.load(params)});
  protected readonly item = computed(() => this.detail.hasValue() ? this.detail.value() : this.displayed()?.summary);
  protected readonly description = computed(() => this.detail.hasValue() ? this.detail.value().description : null);
  protected readonly portrait = computed(() => this.displayed()?.key.sourceId !== 'peppercarrot');
  protected readonly closeIcon = LucideX;
  protected readonly arrow = LucideArrowUpRight;
  protected readonly problem = problem;
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');
  private readonly surface = viewChild.required<ElementRef<HTMLElement>>('surface');
  private readonly coverSlot = viewChild<ElementRef<HTMLElement>>('coverSlot');
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');
  private readonly coverActor = viewChild.required<ElementRef<HTMLElement>>('coverActor');
  private readonly titleActor = viewChild.required<ElementRef<HTMLElement>>('titleActor');
  private readonly reduce = this.window.matchMedia('(prefers-reduced-motion: reduce)');
  private readonly motion = new TitleMorph(this.window, frame => this.render(frame));
  private mode: 'closed' | 'preview' | 'closing' | 'page' | 'finishing' = 'closed';
  private baseCover = {width: 1, height: 1};
  private viewportOffset = {x: 0, y: 0};
  private readonly sourceUrl = signal<string | undefined>(undefined);
  private fade?: Animation;
  private generation = 0;

  constructor() {
    effect(() => {
      const selected = this.state.selection();
      const pageReady = this.state.pageReady();
      const openingPage = this.state.openingPage();
      if (selected && !openingPage) {
        this.displayed.set(selected);
        if (this.mode === 'closed' || this.mode === 'closing') {
          const generation = ++this.generation;
          afterNextRender(() => {
            if (generation === this.generation && this.state.selection() === selected && !this.state.openingPage()) this.show();
          }, {injector: this.injector});
        }
      } else if (selected && openingPage && pageReady && this.mode !== 'page') {
        const generation = ++this.generation;
        afterNextRender(() => {
          if (generation === this.generation && this.state.selection() === selected && this.state.openingPage()) this.expandToPage();
        }, {injector: this.injector});
      } else if (!selected && this.mode !== 'closed' && this.mode !== 'closing' && this.mode !== 'finishing') {
        ++this.generation;
        this.collapse();
      } else if (!selected && this.mode === 'closed') {
        ++this.generation;
        this.displayed.set(undefined);
      }
    });
    const events = new AbortController();
    this.window.addEventListener('resize', () => {
      if (this.mode === 'preview' && this.displayed()) {
        this.fade?.cancel();
        this.motion.snap(this.previewFrame());
        this.moving.set(false);
      }
    }, {signal: events.signal});
    this.reduce.addEventListener('change', () => {
      if (!this.reduce.matches) return;
      if (this.mode === 'preview') {
        this.motion.snap(this.previewFrame());
        this.moving.set(false);
      } else if (this.mode === 'closing') {
        this.motion.stop();
        this.fadeOut(() => this.finishClose(false));
      } else if (this.mode === 'page') {
        this.mode = 'finishing';
        this.motion.stop();
        this.state.pageFinished();
        this.fadeOut(() => this.finishClose(true));
      }
    }, {signal: events.signal});
    inject(DestroyRef).onDestroy(() => {
      events.abort();
      this.fade?.cancel();
      this.motion.stop();
      this.restoreOrigin();
    });
  }

  protected cancel(event: Event) {
    event.preventDefault();
    this.state.dismiss();
  }

  protected backdrop(event: MouseEvent) {
    if (event.target === this.dialog().nativeElement) this.state.dismiss();
  }

  protected trapFocus(event: KeyboardEvent) {
    if (event.key !== 'Tab') return;
    const dialog = this.dialog().nativeElement;
    const controls = Array.from(dialog.querySelectorAll<HTMLElement>(
      'button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]',
    )).filter(element => element.getClientRects().length > 0);
    const first = controls[0];
    const last = controls[controls.length - 1];
    const active = this.document.activeElement;
    if (event.shiftKey && (active === first || !dialog.contains(active))) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && (active === last || !dialog.contains(active))) {
      event.preventDefault();
      first?.focus();
    }
  }

  protected openPage(event: MouseEvent) {
    if (event.button || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    this.state.goToPage();
  }

  protected imageUrl() {
    return this.sourceUrl() ?? this.item()?.coverUrl ?? null;
  }

  private show() {
    const dialog = this.dialog().nativeElement;
    const wasClosing = this.mode === 'closing';
    this.fade?.cancel();
    this.mode = 'preview';
    if (!dialog.open) dialog.showModal();
    const origin = this.displayed()?.origin;
    this.sourceUrl.set(origin?.card.querySelector<HTMLImageElement>('img')?.currentSrc || undefined);
    const target = this.previewFrame();
    this.baseCover = {width: target.coverWidth, height: target.coverHeight};
    this.titleActor().nativeElement.style.width = this.heading()!.nativeElement.getBoundingClientRect().width + 'px';
    const source = this.originFrame();
    this.moving.set(true);
    if (origin?.card.isConnected) origin.card.style.visibility = 'hidden';
    if (this.reduce.matches || !source || !this.window.requestAnimationFrame) {
      this.motion.snap(target);
      this.moving.set(false);
      this.fadeIn();
      return;
    }
    if (!wasClosing) this.motion.start(source);
    this.motion.to(target, () => {
      this.moving.set(false);
    });
  }

  private previewFrame(): MorphFrame {
    const viewport = this.dialog().nativeElement.getBoundingClientRect();
    this.viewportOffset = {x: viewport.x, y: viewport.y};
    const panel = this.panel()!.nativeElement.getBoundingClientRect();
    const cover = this.coverSlot()!.nativeElement.getBoundingClientRect();
    const title = this.heading()!.nativeElement.getBoundingClientRect();
    return {
      x: panel.x, y: panel.y, width: panel.width, height: panel.height, radius: 22,
      coverX: cover.x, coverY: cover.y, coverWidth: cover.width, coverHeight: cover.height,
      titleX: title.x, titleY: title.y, content: 1, shade: .7, opacity: 1, titleOpacity: 1,
    };
  }

  private originFrame(): MorphFrame | undefined {
    const origin = this.displayed()?.origin;
    if (!origin?.card.isConnected) return undefined;
    const surface = origin.card.getBoundingClientRect();
    if (surface.bottom < 0 || surface.top > this.window.innerHeight || !surface.width) return undefined;
    const cover = origin.card.querySelector('app-cover')?.getBoundingClientRect() ?? origin.cover;
    const title = origin.card.querySelector('h2')?.getBoundingClientRect() ?? origin.heading;
    return {
      x: surface.x, y: surface.y, width: surface.width, height: surface.height, radius: 16,
      coverX: cover.x, coverY: cover.y, coverWidth: cover.width, coverHeight: cover.height,
      titleX: title.x, titleY: title.y, content: 0, shade: 0, opacity: 1, titleOpacity: 0,
    };
  }

  private collapse() {
    this.mode = 'closing';
    this.fade?.cancel();
    if (!this.moving()) {
      const frame = this.previewFrame();
      this.baseCover = {width: frame.coverWidth, height: frame.coverHeight};
      this.titleActor().nativeElement.style.width = this.heading()!.nativeElement.getBoundingClientRect().width + 'px';
      this.motion.start(frame);
    }
    this.moving.set(true);
    const target = this.originFrame();
    if (this.reduce.matches || !this.window.requestAnimationFrame || !target) {
      this.fadeOut(() => this.finishClose(false));
    } else this.motion.to(target, () => this.finishClose(false));
  }

  private expandToPage() {
    const main = this.document.querySelector<HTMLElement>('[data-title-page]');
    const cover = main?.querySelector<HTMLElement>('[data-title-cover]');
    const heading = main?.querySelector<HTMLElement>('[data-title-heading]');
    if (!main || !cover || !heading) {
      this.mode = 'finishing';
      this.state.pageFinished();
      this.finishClose(true);
      return;
    }
    if (!this.moving()) {
      const frame = this.previewFrame();
      this.baseCover = {width: frame.coverWidth, height: frame.coverHeight};
      this.motion.start(frame);
    }
    this.mode = 'page';
    this.moving.set(true);
    const box = main.getBoundingClientRect();
    const image = cover.getBoundingClientRect();
    const title = heading.getBoundingClientRect();
    const target: MorphFrame = {
      x: 0, y: Math.max(0, box.y), width: this.window.innerWidth, height: this.window.innerHeight,
      radius: 0, coverX: image.x, coverY: image.y, coverWidth: image.width, coverHeight: image.height,
      titleX: title.x, titleY: title.y, content: 0, shade: 0, opacity: 1, titleOpacity: 1,
    };
    const finish = () => {
      this.mode = 'finishing';
      this.state.pageFinished();
      this.fadeOut(() => this.finishClose(true));
    };
    if (this.reduce.matches || !this.window.requestAnimationFrame) {
      finish();
    } else this.motion.to(target, finish);
  }

  private finishClose(page: boolean) {
    const selected = this.displayed();
    this.mode = 'closed';
    this.motion.stop();
    this.fade?.cancel();
    this.restoreOrigin();
    this.dialog().nativeElement.close();
    this.displayed.set(undefined);
    this.sourceUrl.set(undefined);
    if (page) {
      this.document.querySelector<HTMLElement>('[data-title-heading]')?.focus({preventScroll: true});
    } else {
      this.window.scrollTo(...(selected?.returnTo.position ?? [0, 0]));
      const trigger = selected?.origin?.trigger;
      if (trigger?.isConnected) trigger.focus({preventScroll: true});
      else {
        const fallback = selected ? findPreviewTrigger(this.document, selected.returnTo) : undefined;
        (fallback ?? this.document.querySelector<HTMLElement>('main h1'))?.focus({preventScroll: true});
      }
    }
  }

  private restoreOrigin() {
    const card = this.displayed()?.origin?.card;
    if (card) card.style.removeProperty('visibility');
  }

  private render(frame: MorphFrame) {
    const surface = this.surface().nativeElement;
    const cover = this.coverActor().nativeElement;
    const title = this.titleActor().nativeElement;
    const viewportOffset = this.viewportOffset;
    surface.style.transform = `translate3d(${frame.x - viewportOffset.x}px,${frame.y - viewportOffset.y}px,0) scale(${Math.max(1, frame.width)},${Math.max(1, frame.height)})`;
    surface.style.borderRadius = `${frame.radius / Math.max(1, frame.width)}px / ${frame.radius / Math.max(1, frame.height)}px`;
    surface.style.opacity = String(frame.opacity);
    const scaleX = Math.max(.001, frame.coverWidth / this.baseCover.width);
    const scaleY = Math.max(.001, frame.coverHeight / this.baseCover.height);
    cover.style.width = this.baseCover.width + 'px';
    cover.style.height = this.baseCover.height + 'px';
    cover.style.transform = `translate3d(${frame.coverX - viewportOffset.x}px,${frame.coverY - viewportOffset.y}px,0) scale(${scaleX},${scaleY})`;
    const image = cover.querySelector<HTMLElement>('app-cover');
    const uniform = Math.max(scaleX, scaleY);
    if (image) image.style.transform = `scale(${uniform / scaleX},${uniform / scaleY})`;
    cover.style.opacity = String(frame.opacity);
    title.style.transform = `translate3d(${frame.titleX - viewportOffset.x}px,${frame.titleY - viewportOffset.y}px,0)`;
    title.style.opacity = String(frame.titleOpacity);
    this.dialog().nativeElement.style.setProperty('--preview-shade', String(frame.shade));
    this.dialog().nativeElement.style.setProperty('--preview-content', String(Math.max(0, Math.min(1, frame.content))));
  }

  private fadeIn() {
    this.fade = this.dialog().nativeElement.animate?.([{opacity: 0}, {opacity: 1}], {duration: 120});
  }

  private fadeOut(finish: () => void) {
    if (!this.dialog().nativeElement.animate) { finish(); return; }
    this.fade = this.dialog().nativeElement.animate([{opacity: 1}, {opacity: 0}], {duration: 120, fill: 'forwards'});
    this.fade.finished.then(finish).catch(() => {});
  }
}
