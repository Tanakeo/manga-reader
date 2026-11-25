import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { Api, CatalogQuery, CatalogResponse, problem, TitleSummary } from './api';

interface SavedFeed { response: CatalogResponse; scroll: number; savedAt: number; }
const keyOf = (query: CatalogQuery) => JSON.stringify(query);
const unique = (items: TitleSummary[]) => [...new Map(items.map(item =>
  [item.workId ?? JSON.stringify([item.sourceId, item.externalTitleId]), item])).values()];

@Injectable({providedIn: 'root'})
export class CatalogCache {
  private readonly window = inject(DOCUMENT).defaultView;
  private readonly entries = new Map<string, SavedFeed>();

  get(query: CatalogQuery) {
    const key = keyOf(query);
    let saved = this.entries.get(key);
    try { saved ??= JSON.parse(this.window?.sessionStorage.getItem('kadr.catalog.works.' + key) ?? 'null'); } catch { /* Optional cache. */ }
    if (!saved || Date.now() - saved.savedAt > 10 * 60_000
      || (saved.response.page.expiresAt && Date.parse(saved.response.page.expiresAt) <= Date.now())) return undefined;
    return saved;
  }

  put(query: CatalogQuery, response: CatalogResponse, scroll: number) {
    const key = keyOf(query);
    const saved = {response, scroll, savedAt: Date.now()};
    this.entries.delete(key);
    this.entries.set(key, saved);
    try {
      while (this.entries.size > 6) {
        const oldest = this.entries.keys().next().value!;
        this.entries.delete(oldest);
        this.window?.sessionStorage.removeItem('kadr.catalog.works.' + oldest);
      }
      this.window?.sessionStorage.setItem('kadr.catalog.works.' + key, JSON.stringify(saved));
    } catch { /* Keep the in-memory copy if browser storage is full. */ }
  }
  remove(query:CatalogQuery) {
    const key=keyOf(query);
    this.entries.delete(key);
    try {this.window?.sessionStorage.removeItem('kadr.catalog.works.'+key);}catch { /* Optional cache. */ }
  }
}

/** One cancellable feed per catalogue page; saved snapshots outlive the page. */
@Injectable()
export class CatalogFeed {
  private readonly api = inject(Api);
  private readonly cache = inject(CatalogCache);
  private readonly window = inject(DOCUMENT).defaultView;
  readonly data = signal<CatalogResponse | undefined>(undefined);
  readonly isLoading = signal(false);
  readonly error = signal<unknown>(undefined);
  readonly loadingMore = signal(false);
  readonly moreError = signal('');
  readonly notice = signal('');
  private query?: CatalogQuery;
  private subscription?: Subscription;
  private generation = 0;
  private cursors = new Set<string>();

  hasValue() { return !!this.data(); }
  value() { return this.data()!; }
  save() {
    if (this.query && this.data()) this.cache.put(this.query, this.data()!, this.window?.scrollY ?? 0);
  }
  activate(query: CatalogQuery, restore: boolean): number | undefined {
    if (this.query && keyOf(query) === keyOf(this.query)) return undefined;
    this.save();
    this.cancel();
    this.query = query;
    this.notice.set('');
    this.error.set(undefined);
    this.moreError.set('');
    this.cursors.clear();
    const cached = restore ? this.cache.get(query) : undefined;
    this.data.set(cached?.response);
    if (!cached) this.fetch(false);
    return cached?.scroll;
  }
  reload() { this.cancel(); this.cursors.clear(); this.data.set(undefined); this.fetch(false); }
  more() {
    const page = this.data()?.page;
    if (!page?.hasMore || !page.nextCursor || this.loadingMore() || this.isLoading()) return;
    if (this.cursors.has(page.nextCursor)) { this.moreError.set('Источник вернул повторную порцию. Обновите каталог.'); return; }
    this.fetch(true);
  }
  destroy() { this.save(); this.cancel(); }
  private cancel() {
    ++this.generation;
    this.subscription?.unsubscribe();
    this.isLoading.set(false);
    this.loadingMore.set(false);
  }
  private fetch(more: boolean, restarted = false) {
    if (!this.query) return;
    const generation = ++this.generation;
    const cursor = more ? this.data()?.page.nextCursor ?? undefined : undefined;
    const snapshot = this.data()?.page.snapshotId;
    this.isLoading.set(!more);
    this.loadingMore.set(more);
    this.error.set(undefined);
    this.moreError.set('');
    this.subscription = this.api.catalog(this.query, cursor).subscribe({
      next: response => {
        if (generation !== this.generation) return;
        this.isLoading.set(false);
        this.loadingMore.set(false);
        if (more && response.page.snapshotId !== snapshot) {
          this.moreError.set('Снимок каталога изменился. Обновите список.'); return;
        }
        if (cursor) this.cursors.add(cursor);
        this.data.set({...response, items: unique([...(more ? this.data()?.items ?? [] : []), ...response.items])});
        if (this.query) this.cache.put(this.query, this.data()!, this.window?.scrollY ?? 0);
      },
      error: error => {
        if (generation !== this.generation) return;
        this.isLoading.set(false);
        this.loadingMore.set(false);
        const issue = problem(error);
        if (!restarted && ['CATALOG_SNAPSHOT_STALE', 'CATALOG_CURSOR_EXPIRED'].includes(issue.code)) {
          this.notice.set('Каталог обновился. Начали новую выдачу с прежними фильтрами.');
          this.data.set(undefined);
          this.cursors.clear();
          this.fetch(false, true);
        } else if (more) this.moreError.set(issue.detail);
        else this.error.set(error);
      },
    });
  }
}
