import { afterNextRender, Component, computed, DestroyRef, inject, Injector, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationStart, Router, RouterLink } from '@angular/router';
import { LucideArrowUpRight as ArrowUpRight, LucideChevronDown as ChevronDown, LucideDynamicIcon } from '@lucide/angular';
import { Api, CatalogQuery, problem, Tag, titleLink } from './api';
import { CatalogFeed } from './catalog-feed';
import { CatalogFacts } from './catalog-facts';
import { catalogParams, catalogSorts, normalizeCatalog, readCatalog, tagLimit, tagName } from './catalog-query';
import { Cover, State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { CommandSearch } from './shared/command-search';
import { TitlePreviewLink } from './shared/title-preview-link';
import { TitleDetails } from './shared/title-details';

@Component({
  imports: [FormsModule, RouterLink, LucideDynamicIcon, Cover, State, CatalogFacts, LocalizedTitle, CommandSearch, TitlePreviewLink],
  templateUrl: './catalog.html',
  providers: [CatalogFeed],
})
export class Catalog {
  private readonly api = inject(Api);
  private readonly details = inject(TitleDetails);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private normalization?: ReturnType<typeof setTimeout>;
  protected readonly query = signal(readCatalog(this.route.snapshot.queryParamMap), {
    equal: (a, b) => a.sourceId === b.sourceId && a.q === b.q && a.sort === b.sort
      && a.tagIds.join(',') === b.tagIds.join(',') && a.chapterLanguages?.join(',') === b.chapterLanguages?.join(','),
  });
  protected readonly titleLanguage = inject(TitleLocale).language;
  protected readonly sourceId = computed(() => this.query().sourceId);
  protected readonly arrow = ArrowUpRight;
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
  protected readonly response = inject(CatalogFeed);
  protected readonly loadingMore = this.response.loadingMore;
  protected readonly moreError = this.response.moreError;
  protected readonly currentPage = computed(() => this.response.data()?.page);
  protected readonly results = {
    hasValue: () => this.response.hasValue(),
    isLoading: () => this.response.isLoading(),
    error: () => this.response.error(),
    reload: () => this.response.reload(),
    value: computed(() => this.response.data()?.items ?? []),
  };
  protected loadMore() { this.response.more(); }
  protected sourceName(id: string) {
    return this.sources.hasValue() ? this.sources.value().find(item => item.sourceId === id)?.sourceName ?? id : id;
  }

  protected setChapterLanguage(language: string) {
    this.apply({...this.query(), chapterLanguages: language ? [language] : []});
  }

  protected readonly featured = computed(() => {
    const results = this.results;
    if (!results.hasValue() || results.value().length !== 1) return undefined;
    const item = results.value()[0];
    return item.sourceId === 'peppercarrot' ? item : undefined;
  });
  protected readonly detail = rxResource({
    params: () => this.featured(),
    stream: ({params}) => this.details.load(params),
  });

  constructor() {
    const injector = inject(Injector);
    let first = true;
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe(params => {
      clearTimeout(this.normalization);
      const query = readCatalog(params);
      this.query.set(query);
      const restore = first || this.router.getCurrentNavigation()?.trigger === 'popstate';
      const scroll = this.response.activate(query, restore);
      first = false;
      if (scroll !== undefined) afterNextRender(() => window.scrollTo({top: scroll, behavior: 'instant'}), {injector});
      if (params.getAll('sourceId').join(',') !== query.sourceId ||
          params.getAll('q').join(',') !== query.q || params.getAll('sort').join(',') !== query.sort ||
          params.getAll('tagId').join(',') !== query.tagIds.join(',') ||
          params.getAll('chapterLanguage').join(',') !== query.chapterLanguages?.join(',') ||
          ['sourceId', 'q', 'sort', 'tagId', 'chapterLanguage'].some(key => params.has(key) && params.get(key) === '')) {
        this.normalization = setTimeout(() => this.apply(query, true));
      }
    });
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event => {
      if (event instanceof NavigationStart) {
        clearTimeout(this.normalization);
        this.response.save();
      }
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.normalization);
      this.response.destroy();
    });
  }

  protected countLabel(count: number) {
    const form = new Intl.PluralRules('ru').select(count);
    return count + ' ' + (form === 'one' ? 'тайтл' : form === 'few' ? 'тайтла' : 'тайтлов') + ' в каталоге';
  }

  protected reset() {
    this.apply({sourceId: this.sourceId(), q: '', tagIds: [], sort: '', chapterLanguages: []});
  }

  protected setSource(sourceId: string) {
    this.apply({sourceId, q: this.query().q, tagIds: [], sort: '', chapterLanguages: this.query().chapterLanguages});
  }

  protected setSearch(q: string) {
    this.apply({...this.query(), q});
  }

  protected setSort(sort: string) {
    this.apply({...this.query(), sort});
  }

  protected toggleTag(id: string) {
    const ids = this.query().tagIds;
    if (ids.includes(id)) this.removeTags([id]);
    else if (ids.length < tagLimit) this.apply({...this.query(), tagIds: [...ids, id]});
  }

  protected removeTags(ids = this.query().tagIds) {
    this.apply({...this.query(), tagIds: this.query().tagIds.filter(id => !ids.includes(id))});
  }

  private apply(query: CatalogQuery, replaceUrl = false) {
    clearTimeout(this.normalization);
    const normalized = normalizeCatalog(query);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: catalogParams(normalized),
      replaceUrl,
    });
  }
}
