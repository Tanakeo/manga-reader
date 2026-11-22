import { afterNextRender, Component, computed, DestroyRef, inject, Injector, input, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  LucideArrowRight, LucideCheck, LucideDynamicIcon, LucideGlobe, LucidePlug, LucidePlus,
} from '@lucide/angular';
import { finalize, map, Observable, tap } from 'rxjs';
import { Api, Extension, problem, Source, SourceIdentity } from './api';
import { State } from './common';
import { MagneticButton } from './magnetic-button';
import { CommandSearch } from './shared/command-search';
import { SyncProgress } from './sync-progress';

type BrowseTab = 'sources' | 'extensions';

@Component({
  imports: [RouterLink, LucideDynamicIcon, State, MagneticButton, CommandSearch, SyncProgress],
  templateUrl: './browse.html',
  styleUrl: './browse.css',
})
export class Browse {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroy = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly revision = signal(0);
  readonly tab = input<string>();
  readonly q = input<string>();
  protected readonly searchQuery = computed(() => (this.q() ?? '').trim().slice(0, 120));
  protected readonly activeTab = computed<BrowseTab>(() => this.tab() === 'extensions' ? 'extensions' : 'sources');
  protected readonly sources = signal<Source[] | undefined>(undefined);
  protected readonly extensions = signal<Extension[] | undefined>(undefined);
  protected readonly filteredSources = computed(() => this.sources()?.filter(item => this.matches(item)) ?? []);
  protected readonly filteredExtensions = computed(() => this.extensions()?.filter(item => this.matches(item)) ?? []);
  protected readonly sourcesRequest = rxResource({
    params: this.revision,
    stream: () => this.api.sources().pipe(tap(items => this.sources.set(items))),
  });
  protected readonly extensionsRequest = rxResource({
    params: this.revision,
    stream: () => this.api.extensions().pipe(tap(items => this.extensions.set(items))),
  });

  protected readonly busy = signal(new Set<string>());
  protected readonly errors = signal(new Map<string, string>());
  protected readonly notice = signal('');
  protected readonly arrow = LucideArrowRight;
  protected readonly check = LucideCheck;
  protected readonly globe = LucideGlobe;
  protected readonly plug = LucidePlug;
  protected readonly plus = LucidePlus;
  protected readonly problem = problem;

  private matches(item: SourceIdentity) {
    const query = this.searchQuery().toLocaleLowerCase();
    return [item.sourceName, item.sourceId].some(value => value.toLocaleLowerCase().includes(query));
  }

  protected setSearch(query: string) {
    void this.router.navigate([], {
      relativeTo: this.route, queryParams: {q: query.trim() || null}, queryParamsHandling: 'merge',
    });
  }

  protected selectTab(tab: BrowseTab) {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {tab: tab === 'sources' ? null : tab},
      queryParamsHandling: 'merge',
    });
  }

  protected moveTab(event: KeyboardEvent, sources: HTMLButtonElement, extensions: HTMLButtonElement) {
    let next: BrowseTab;
    switch (event.key) {
      case 'Home': next = 'sources'; break;
      case 'End': next = 'extensions'; break;
      case 'ArrowLeft':
      case 'ArrowRight': next = event.target === sources ? 'extensions' : 'sources'; break;
      default: return;
    }
    event.preventDefault();
    (next === 'sources' ? sources : extensions).focus();
    this.selectTab(next);
  }

  protected changeConnection(source: SourceIdentity, connect: boolean, focusAfter?: HTMLButtonElement) {
    const id = source.sourceId;
    if (this.busy().has(id)) return;
    const trigger = focusAfter?.ownerDocument.activeElement;
    this.busy.update(items => new Set(items).add(id));
    this.errors.update(items => {
      const next = new Map(items);
      next.delete(id);
      return next;
    });
    this.notice.set('');

    const request: Observable<Source | null> = connect
      ? this.api.connectSource(id)
      : this.api.disconnectSource(id).pipe(map(() => null));
    request.pipe(takeUntilDestroyed(this.destroy), finalize(() => {
      this.busy.update(items => {
        const next = new Set(items);
        next.delete(id);
        return next;
      });
    })).subscribe({
      next: connected => {
        this.sources.update(items => {
          const remaining = items?.filter(item => item.sourceId !== id) ?? [];
          return connected ? [...remaining, connected].sort((a, b) => a.sourceId.localeCompare(b.sourceId)) : remaining;
        });
        this.extensions.update(items => items?.map(item => item.sourceId === id ? {...item, enabled: connect} : item));
        this.revision.update(value => value + 1);
        this.notice.set(connect ? `Источник «${source.sourceName}» подключён.` : `Источник «${source.sourceName}» отключён.`);
        if (focusAfter && trigger === focusAfter.ownerDocument.activeElement && this.activeTab() === 'sources') {
          afterNextRender(() => focusAfter.focus(), {injector: this.injector});
        }
      },
      error: error => this.errors.update(items => new Map(items).set(id, problem(error).detail)),
    });
  }
}
