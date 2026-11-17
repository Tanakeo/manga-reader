import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationStart, Router, RouterLink } from '@angular/router';
import { LucideArrowUpRight as ArrowUpRight, LucideChevronDown as ChevronDown, LucideDynamicIcon, LucideSearch as Search } from '@lucide/angular';
import { Api, CatalogQuery, problem, Tag, titleLink } from './api';
import { CatalogFacts } from './catalog-facts';
import { catalogParams, catalogSorts, normalizeCatalog, readCatalog, tagLimit, tagName } from './catalog-query';
import { Cover, State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';

@Component({
  imports: [FormsModule, RouterLink, LucideDynamicIcon, Cover, State, CatalogFacts, LocalizedTitle],
  templateUrl: './catalog.html',
})
export class Catalog {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private timer?: ReturnType<typeof setTimeout>;
  private normalization?: ReturnType<typeof setTimeout>;
  protected readonly query = signal(readCatalog(this.route.snapshot.queryParamMap), {
    equal: (a, b) => a.sourceId === b.sourceId && a.q === b.q && a.sort === b.sort
      && a.tagIds.join(',') === b.tagIds.join(','),
  });
  protected readonly search = signal('');
  protected readonly titleLanguage = inject(TitleLocale).language;
  protected readonly sourceId = computed(() => this.query().sourceId);
  protected readonly arrow = ArrowUpRight;
  protected readonly magnify = Search;
  protected readonly chevron = ChevronDown;
  protected readonly problem = problem;
  protected readonly titleLink = titleLink;
  protected readonly tagName = tagName;
  protected readonly sortNames = catalogSorts;
  protected readonly maxTags = tagLimit;

  protected readonly sources = rxResource({stream: () => this.api.sources()});
  protected readonly selectedSource = computed(() => this.sources.hasValue()
    ? this.sources.value().find(source => source.sourceId === this.sourceId()) : undefined);
  protected readonly searchLabel = computed(() => this.sourceId()
    ? 'Поиск в ' + (this.selectedSource()?.sourceName ?? this.sourceId()) : 'Поиск по всем источникам');
  protected readonly tags = rxResource({
    params: () => this.selectedSource()?.supportsTags ? this.sourceId() : undefined,
    stream: ({params}) => this.api.tags(params),
  });
  protected readonly groups = computed(() => {
    if (!this.tags.hasValue()) return [];
    const language = this.titleLanguage();
    const names: Record<string, string> = {genre: 'Жанры', theme: 'Темы', format: 'Форматы', content: 'Содержание'};
    const grouped = new Map<string, Tag[]>();
    for (const tag of [...this.tags.value()].sort((a, b) =>
      tagName(a, language).localeCompare(tagName(b, language), language))) {
      const name = names[tag.group] ?? (tag.group || 'Другое');
      const items = grouped.get(name) ?? [];
      items.push(tag);
      grouped.set(name, items);
    }
    return [...grouped].map(([name, tags]) => ({name, tags}));
  });
  protected readonly selectedTags = computed(() => {
    const known = new Map(this.tags.hasValue() ? this.tags.value().map(tag => [tag.id, tag]) : []);
    return this.query().tagIds.map(id => {
      const tag = known.get(id);
      return {id, name: tag ? tagName(tag, this.titleLanguage()) : 'Тег ' + id.slice(0, 8)};
    });
  });
  protected readonly titleContext = computed(() => ({
    ...catalogParams(this.query(), 'catalogSource'), fromCatalog: '1',
  }));
  protected readonly results = rxResource({
    params: this.query,
    stream: ({params}) => this.api.catalog(params),
  });

  protected readonly featured = computed(() => {
    const results = this.results;
    if (!results.hasValue() || results.value().length !== 1) return undefined;
    const item = results.value()[0];
    return item.sourceId === 'mangadex' ? undefined : item;
  });
  protected readonly detail = rxResource({
    params: () => this.featured(),
    stream: ({params}) => this.api.title(params),
  });

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe(params => {
      clearTimeout(this.timer);
      clearTimeout(this.normalization);
      const query = readCatalog(params);
      this.query.set(query);
      if (this.search().trim() !== query.q || this.router.currentNavigation()?.trigger === 'popstate') {
        this.search.set(query.q);
      }
      if (params.getAll('sourceId').join(',') !== query.sourceId ||
          params.getAll('q').join(',') !== query.q || params.getAll('sort').join(',') !== query.sort ||
          params.getAll('tagId').join(',') !== query.tagIds.join(',') ||
          ['sourceId', 'q', 'sort', 'tagId'].some(key => params.has(key) && params.get(key) === '')) {
        this.normalization = setTimeout(() => this.apply(query, true));
      }
    });
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event instanceof NavigationStart) {
        clearTimeout(this.timer);
        clearTimeout(this.normalization);
      }
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timer);
      clearTimeout(this.normalization);
    });
  }

  protected countLabel(count: number) {
    const form = new Intl.PluralRules('ru').select(count);
    return count + ' ' + (form === 'one' ? 'тайтл' : form === 'few' ? 'тайтла' : 'тайтлов') + ' в каталоге';
  }

  protected reset() {
    this.apply({sourceId: this.sourceId(), q: '', tagIds: [], sort: ''});
  }

  protected setSource(sourceId: string) {
    this.apply({sourceId, q: this.search(), tagIds: [], sort: ''});
  }

  protected editSearch(q: string) {
    this.search.set(q);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.apply({...this.query(), q}, true), 220);
  }

  protected setSort(sort: string) {
    this.apply({...this.query(), q: this.search(), sort});
  }

  protected toggleTag(id: string) {
    const ids = this.query().tagIds;
    if (ids.includes(id)) this.removeTags([id]);
    else if (ids.length < tagLimit) this.apply({...this.query(), q: this.search(), tagIds: [...ids, id]});
  }

  protected removeTags(ids = this.query().tagIds) {
    this.apply({...this.query(), q: this.search(), tagIds: this.query().tagIds.filter(id => !ids.includes(id))});
  }

  private apply(query: CatalogQuery, replaceUrl = false) {
    clearTimeout(this.timer);
    clearTimeout(this.normalization);
    const normalized = normalizeCatalog(query);
    if (!replaceUrl) this.search.set(normalized.q);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: catalogParams(normalized),
      replaceUrl,
    });
  }
}
