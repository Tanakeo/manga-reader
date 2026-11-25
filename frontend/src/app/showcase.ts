import { afterNextRender, Component, computed, DestroyRef, DOCUMENT, inject, Injector, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationStart, Router, RouterLink } from '@angular/router';
import { Api, Collection, problem, titleLink } from './api';
import { Cover, State } from './common';
import { LocalizedTitle, TitleLocale } from './title-locale';
import { TitlePreviewLink } from './shared/title-preview-link';
import { WorkFacts } from './work-facts';
import { DatePipe } from '@angular/common';
import { map, of } from 'rxjs';
import { CatalogCache } from './catalog-feed';

@Component({
  selector: 'app-showcase',
  imports: [RouterLink, Cover, State, LocalizedTitle, TitlePreviewLink, WorkFacts, DatePipe],
  template: `
    <main class="wrap showcase" id="main-content" tabindex="-1">
      <div class="showcase-intro">
        <div><p class="eyebrow">Найдите свою следующую историю</p><h1 class="page-heading">Манга в кадре</h1></div>
        <form class="showcase-search" (submit)="search($event, searchField.value)">
          <input #searchField type="search" aria-label="Найти мангу" placeholder="Название манги" maxlength="120">
          <button class="button secondary" type="submit">Найти</button>
        </form>
      </div>
      <div class="showcase-controls">
        <nav class="work-tabs" aria-label="Вид каталога"><a class="active" routerLink="/catalog" [queryParams]="{view:'showcase'}" aria-current="page">Подборки</a><a routerLink="/catalog" [queryParams]="{view:'works'}">Все манги</a></nav>
        <label>Язык глав <select [value]="language()" (change)="setLanguage($any($event.target).value)"><option value="">Любой</option><option value="ru">Русский</option><option value="en">Английский</option></select></label>
      </div>
      @if (data.isLoading() && !data.hasValue()) { <app-state kind="loading" artwork message="Собираем подборки…"/> }
      @else if (data.error(); as error) { <app-state kind="error" [message]="problem(error).detail" (retry)="data.reload()"/> }
      @else if (data.hasValue()) {
        @if (data.value().partial) { <p class="showcase-notice" role="status">Подборки по собранным тайтлам. Покрытие источников неполное, доступность части глав уточняется.</p> }
        @for (provider of data.value().providerStates; track provider.provider) {
          @if (provider.error) { <p class="showcase-notice" role="status">Показаны сохранённые метрики MangaDex. {{ provider.error.detail }}</p> }
          @else if (!provider.lastSuccessAt) { <p class="showcase-notice" role="status">Показатели MangaDex обновляются в фоне. Подборки появятся по мере проверки тайтлов.</p> }
        }
        @if (!collectionId() && featured(); as item) {
          <article class="showcase-hero" data-title-card>
            <a class="showcase-hero-cover" [routerLink]="titleLink(item)" [titlePreview]="item" [attr.aria-label]="'Подробнее о ' + (item | localizedTitle:titleLanguage())">
              <app-cover [src]="item.coverUrl" [titleKey]="item.coverTitle ?? item" [label]="'Обложка ' + item.title" [eager]="true"/>
            </a>
            <div class="showcase-hero-copy"><p class="eyebrow">Популярное на MangaDex</p><h2><a [routerLink]="titleLink(item)" [titlePreview]="item">{{ item | localizedTitle:titleLanguage() }}</a></h2>
              @if (item.description) { <p class="showcase-description">{{ item.description }}</p> }
              <app-work-facts [item]="item" kind="popular"/>
              <a class="button primary" [routerLink]="titleLink(item)">Выбрать источник и читать</a>
            </div>
          </article>
        }
        @for (collection of collections(); track collection.id + (collection.tagId ?? '')) {
          <section class="showcase-section" [attr.aria-label]="collection.title">
            <div class="showcase-heading"><div><h2>{{ collection.title }}</h2><p class="muted">{{ collection.basis }}</p></div>
              @if (!collectionId()) { <a class="text-link" routerLink="/catalog" [queryParams]="{view:'showcase',collection:collection.id,tagId:collection.tagId,chapterLanguage:language() || null}">Смотреть все →</a> }
              @else { <a class="text-link" routerLink="/catalog" [queryParams]="{view:'showcase',chapterLanguage:language() || null}">К подборкам</a> }
            </div>
            @if (collection.state === 'STALE') { <p class="muted" role="status">Сохранённые показатели ожидают обновления.</p> }
            @if (collection.items.length) {
              <div class="catalog-grid">
                @for (item of collection.items; track item.workId) {
                  <article class="catalog-card" data-title-card>
                    <a [routerLink]="titleLink(item)" [titlePreview]="item" [attr.aria-label]="'Открыть ' + (item | localizedTitle:titleLanguage())"><app-cover [src]="item.coverUrl" [titleKey]="item.coverTitle ?? item" label="" [showFallback]="false"/></a>
                    <div><h2><a [routerLink]="titleLink(item)" [titlePreview]="item">{{ item | localizedTitle:titleLanguage() }}</a></h2><app-work-facts [item]="item" [kind]="collection.id"/>
                      @if (collection.id === 'latest' && item.latestChapterAt) { <p class="muted">Глава от {{ item.latestChapterAt | date:'dd.MM.yyyy' }}</p> }
                    </div>
                  </article>
                }
              </div>
              <p class="showcase-covers-empty muted" role="status">Обложки этой подборки временно недоступны.</p>
            } @else { <p class="showcase-empty">{{ collection.state === 'UNAVAILABLE' ? 'Показатели ещё загружаются. Можно открыть все манги.' : 'Пока нет подтверждённых тайтлов для выбранного языка.' }}</p> }
            @if (collectionId() && collection.page.hasMore) { <button class="button secondary" (click)="more(collection)" [disabled]="loadingMore()">{{ loadingMore() ? 'Загружаем…' : 'Показать ещё' }}</button> }
          </section>
        }
        @if (moreError()) { <p class="error" role="alert">{{ moreError() }}</p> }
      }
    </main>
  `,
})
export class Showcase {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly cache=inject(CatalogCache);
  private readonly window=inject(DOCUMENT).defaultView;
  private readonly injector=inject(Injector);
  private readonly params = toSignal(inject(ActivatedRoute).queryParamMap, {requireSync:true});
  protected readonly language = computed(() => this.params().get('chapterLanguage') ?? '');
  protected readonly collectionId = computed(() => this.params().get('collection') ?? '');
  protected readonly titleLanguage = inject(TitleLocale).language;
  protected readonly titleLink = titleLink;
  protected readonly problem = problem;
  protected readonly loadingMore = signal(false);
  protected readonly moreError = signal('');
  private readonly extra = signal<{key:string;collection:Collection} | undefined>(undefined);
  protected readonly data = rxResource({
    params: () => ({language:this.language(),id:this.collectionId(),tag:this.params().get('tagId') ?? ''}),
    stream: ({params}) => params.id ? this.loadCollection(params).pipe(
      mapCollection()) : this.api.showcase(params.language),
  });
  protected readonly collections = computed(() => {
    const original=this.data.hasValue()?this.data.value().collections:[];
    const extra=this.extra();
    return extra?.key===this.key()?[extra.collection]:original;
  });
  protected readonly featured = computed(() => this.data.hasValue()?this.data.value().collections.find(c=>c.id==='popular')?.items[0]:undefined);
  constructor() {
    this.router.events.pipe(takeUntilDestroyed()).subscribe(event=>{if(event instanceof NavigationStart)this.save();});
    inject(DestroyRef).onDestroy(()=>this.save());
  }
  private cacheQuery(id=this.collectionId(),language=this.language(),tag=this.params().get('tagId') ?? '') {
    return {sourceId:'',q:'',sort:'showcase:'+id,tagIds:tag?[tag]:[],chapterLanguages:language?[language]:[]};
  }
  private loadCollection(params:{id:string;language:string;tag:string}) {
    const saved=this.cache.get(this.cacheQuery(params.id,params.language,params.tag));
    if(saved) {
      afterNextRender(()=>this.window?.scrollTo({top:saved.scroll,behavior:'instant'}),{injector:this.injector});
      return of(saved.response as Collection);
    }
    return this.api.collection(params.id,params.language,params.tag);
  }
  private save() {
    if(this.collectionId() && this.collections()[0])this.cache.put(this.cacheQuery(),this.collections()[0],this.window?.scrollY ?? 0);
  }
  private key() {return JSON.stringify([this.collectionId(),this.language(),this.params().get('tagId')]);}
  protected search(event:Event,value:string) {event.preventDefault();void this.router.navigate(['/catalog'],{queryParams:{view:'works',q:value.trim() || null,chapterLanguage:this.language() || null}});}
  protected setLanguage(language:string) {this.extra.set(undefined);void this.router.navigate([],{queryParams:{chapterLanguage:language || null},queryParamsHandling:'merge'});}
  protected more(collection:Collection) {
    if(this.loadingMore() || !collection.page.nextCursor) return;
    const key=this.key();this.loadingMore.set(true);this.moreError.set('');
    this.api.collection(collection.id,this.language(),collection.tagId,collection.page.nextCursor).subscribe({
      next:response=>{this.loadingMore.set(false);if(key===this.key())this.extra.set({key,collection:{...response,items:[...collection.items,...response.items]}});},
      error:error=>{
        this.loadingMore.set(false);
        if(key!==this.key())return;
        const issue=problem(error);
        this.moreError.set(issue.detail);
        if(['CATALOG_SNAPSHOT_STALE','CATALOG_CURSOR_EXPIRED'].includes(issue.code)) {
          this.cache.remove(this.cacheQuery());this.extra.set(undefined);this.data.reload();
        }
      },
    });
  }
}

function mapCollection() {
  return map((collection:Collection) => ({generatedAt:'',collections:[collection],partial:collection.partial,providerStates:[]}));
}
