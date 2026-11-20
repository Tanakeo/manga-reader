import { DOCUMENT, inject, Injectable, signal, DestroyRef } from '@angular/core';
import { Location } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, NavigationError, NavigationCancel, NavigationStart, Router, Scroll } from '@angular/router';
import { TitleKey, TitleSummary, sameTitle } from '../api';
import { TitleDetails } from './title-details';

export interface PreviewOrigin {
  card: HTMLElement;
  trigger: HTMLAnchorElement;
  surface: DOMRect;
  cover: DOMRect;
  heading: DOMRect;
}

export interface TitleReturn {
  url: string;
  position: [number, number];
  key: string;
  titleLink: boolean;
  actionLink?: boolean;
  historyEntry?: boolean;
}

export interface PreviewSelection {
  key: TitleKey;
  summary?: TitleSummary;
  origin?: PreviewOrigin;
  returnTo: TitleReturn;
  target: string;
}

export function previewKey(key: TitleKey) {
  return JSON.stringify([key.sourceId, key.externalTitleId]);
}

export function findPreviewTrigger(document: Document, context: TitleReturn) {
  const matching = Array.from(document.querySelectorAll<HTMLAnchorElement>('a[data-preview-key]'))
    .filter(link => link.dataset['previewKey'] === context.key);
  return matching.find(link => !!link.closest('h2') === context.titleLink &&
    link.classList.contains('feature-action') === !!context.actionLink) ?? matching[0];
}

@Injectable({providedIn: 'root'})
export class TitlePreviewState {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly document = inject(DOCUMENT);
  private readonly window = this.document.defaultView;
  private readonly details = inject(TitleDetails);
  readonly selection = signal<PreviewSelection | undefined>(undefined);
  readonly openingPage = signal(false);
  readonly pageReady = signal(false);
  readonly navigationIssue = signal('');
  private readonly pageContext = signal<TitleReturn | undefined>(undefined);
  private last?: PreviewSelection;
  private returnTo?: TitleReturn;
  private restore?: TitleReturn;
  private observer?: MutationObserver;
  private restoreTimer?: ReturnType<typeof setTimeout>;
  private dismissing = false;

  constructor() {
    const saved = this.window?.history.state?.kadrTitleReturn as TitleReturn | undefined;
    if (saved && this.safeList(saved.url)) this.returnTo = saved;
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event instanceof NavigationStart) {
        this.stopRestore();
        if (event.navigationTrigger === 'popstate' && event.url === this.returnTo?.url) this.restore = this.returnTo;
      }
      if (event instanceof NavigationEnd) this.sync(event.urlAfterRedirects);
      if (event instanceof Scroll) {
        const selected = this.selection();
        if (selected && !this.openingPage()) queueMicrotask(() => {
          if (this.selection() === selected && !this.openingPage()) this.window?.scrollTo(...selected.returnTo.position);
        });
        if (selected && this.openingPage() && event.routerEvent instanceof NavigationEnd &&
            event.routerEvent.urlAfterRedirects === selected.target) this.pageReady.set(true);
        if (this.restore) queueMicrotask(() => { if (this.restore) this.restoreList(); });
      }
      if (event instanceof NavigationCancel || event instanceof NavigationError) this.dismissing = false;
      if ((event instanceof NavigationCancel || event instanceof NavigationError) && this.openingPage()) {
        this.openingPage.set(false);
        this.pageReady.set(false);
        this.navigationIssue.set('Не удалось открыть страницу. Попробуйте ещё раз.');
      }
    });
    this.sync(this.router.url);
    inject(DestroyRef).onDestroy(() => this.stopRestore());
  }

  open(summary: TitleSummary, trigger: HTMLAnchorElement) {
    if (this.selection() && sameTitle(this.selection()!.key, summary)) return;
    const card = trigger.closest<HTMLElement>('[data-title-card]') ?? trigger;
    const cover = card.querySelector<HTMLElement>('app-cover');
    const heading = card.querySelector<HTMLElement>('h2');
    const origin = cover && heading ? {
      card, trigger, surface: card.getBoundingClientRect(), cover: cover.getBoundingClientRect(),
      heading: heading.getBoundingClientRect(),
    } : undefined;
    const base = this.withoutPreview(this.router.url);
    const returnTo: TitleReturn = {
      url: base, position: [this.window?.scrollX ?? 0, this.window?.scrollY ?? 0],
      key: previewKey(summary), titleLink: !!trigger.closest('h2'),
      actionLink: trigger.classList.contains('feature-action'),
      historyEntry: true,
    };
    const target = new URL(trigger.href, this.document.baseURI);
    this.last = {key: summary, summary, origin, returnTo, target: target.pathname + target.search};
    this.details.remember(summary);
    this.navigationIssue.set('');
    this.dismissing = false;
    this.selection.set(this.last);
    const tree = this.router.parseUrl(base);
    tree.queryParams = {...tree.queryParams, previewSource: summary.sourceId, previewTitle: summary.externalTitleId};
    void this.router.navigateByUrl(tree, {state: {kadrPreviewOrigin: base}});
  }

  dismiss() {
    if (this.dismissing) return;
    const selected = this.selection();
    if (!selected) return;
    this.dismissing = true;
    this.openingPage.set(false);
    this.pageReady.set(false);
    if (selected.returnTo.historyEntry) this.location.back();
    else void this.router.navigateByUrl(this.withoutPreview(this.router.url), {replaceUrl: true});
  }

  goToPage() {
    const selected = this.selection();
    if (!selected || this.openingPage() || this.dismissing) return;
    this.navigationIssue.set('');
    this.returnTo = selected.returnTo;
    this.openingPage.set(true);
    this.pageReady.set(false);
    void this.router.navigateByUrl(selected.target, {
      replaceUrl: true, state: {kadrTitleReturn: selected.returnTo},
    }).catch(() => {
      this.openingPage.set(false);
      this.navigationIssue.set('Не удалось открыть страницу. Попробуйте ещё раз.');
    });
  }

  pageFinished() {
    this.selection.set(undefined);
    this.openingPage.set(false);
    this.pageReady.set(false);
    this.last = undefined;
  }

  pageReturn() {
    return this.pageContext();
  }

  backToList(event: MouseEvent) {
    if (event.button || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const back = this.pageReturn();
    if (!back) return;
    event.preventDefault();
    this.returnTo = back;
    if (back.historyEntry) this.location.back();
    else {
      void this.router.navigateByUrl(back.url, {replaceUrl: true}).then(() => {
        this.restore = back;
        this.restoreList();
      });
    }
  }

  private sync(url: string) {
    this.dismissing = false;
    const context = this.window?.history.state?.kadrTitleReturn as TitleReturn | undefined;
    this.pageContext.set(context && this.safeList(context.url) ? context : undefined);
    const tree = this.router.parseUrl(url);
    const sourceId = tree.queryParams['previewSource'];
    const externalTitleId = tree.queryParams['previewTitle'];
    const path = tree.root.children['primary']?.segments.map(segment => segment.path).join('/') ?? '';
    if (sourceId && externalTitleId && (path === 'catalog' || path === 'library')) {
      const key = {sourceId: String(sourceId), externalTitleId: String(externalTitleId)};
      if (this.selection() && sameTitle(this.selection()!.key, key)) return;
      this.selection.set(this.last && sameTitle(this.last.key, key) ? this.last : {
        key, returnTo: {url: this.withoutPreview(url), position: [0, 0], key: previewKey(key), titleLink: false,
          historyEntry: this.window?.history.state?.kadrPreviewOrigin === this.withoutPreview(url)},
        target: this.router.serializeUrl(this.router.createUrlTree(['/catalog', key.sourceId, key.externalTitleId])),
      });
    } else if (this.openingPage() && url !== this.selection()?.target) {
      this.openingPage.set(false);
      this.pageReady.set(false);
      this.selection.set(undefined);
    } else if (!this.openingPage()) {
      this.selection.set(undefined);
    }
  }

  private withoutPreview(url: string) {
    const tree = this.router.parseUrl(url);
    delete tree.queryParams['previewSource'];
    delete tree.queryParams['previewTitle'];
    return this.router.serializeUrl(tree);
  }

  private safeList(url: string) {
    return /^\/(catalog|library)(?:\?|$)/.test(url);
  }

  private restoreList() {
    const restore = this.restore!;
    const attempt = () => {
      const trigger = findPreviewTrigger(this.document, restore);
      if (!trigger) return;
      this.window?.scrollTo(...restore.position);
      trigger.focus({preventScroll: true});
      this.stopRestore();
    };
    if (!this.observer) {
      this.observer = new MutationObserver(attempt);
      this.observer.observe(this.document.body, {childList: true, subtree: true});
      this.restoreTimer = setTimeout(() => this.stopRestore(), 8000);
    }
    attempt();
  }

  private stopRestore() {
    this.observer?.disconnect();
    this.observer = undefined;
    clearTimeout(this.restoreTimer);
    this.restore = undefined;
  }
}
