import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, problem, WorkDetail, WorkSource } from './api';
import { State } from './common';
import { TitlePage } from './title';
import { WorkFacts } from './work-facts';
import { FormsModule } from '@angular/forms';
import { TitlePreviewState } from './shared/title-preview-state';

@Component({
  imports:[RouterLink,State,TitlePage,WorkFacts,FormsModule],
  template:`
    @if (work.error(); as error) { <main class="wrap section-page"><app-state kind="error" [message]="problem(error).detail" (retry)="work.reload()"/></main> }
    @else if (!work.hasValue()) { <main class="wrap section-page"><app-state kind="loading" artwork message="Загружаем произведение…"/></main> }
    @else {
      <section class="wrap work-source-picker" aria-label="Выбор источника">
        <div><p class="eyebrow">Источники произведения</p><app-work-facts [item]="work.value()"/></div>
        <label>Читать в <select [ngModel]="selected()?.titleId ?? ''" [disabled]="saving()" (ngModelChange)="choose($event)">
          @for (source of work.value().sources ?? []; track source.titleId) { <option [value]="source.titleId" [disabled]="!supportsLanguage(source)">{{ source.sourceName }} · {{ source.supportedLanguages.join(', ') }}{{ source.chapterAvailability?.presence === 'FOUND' ? '' : ' · главы уточняются' }}</option> }
        </select></label>
        <label>Язык глав <select [ngModel]="params().get('chapterLanguage') ?? ''" (ngModelChange)="setLanguage($event)"><option value="">Любой</option><option value="ru">Русский</option><option value="en">Английский</option></select></label>
        @if (issue()) { <p class="error" role="alert">{{ issue() }}</p> }
        @if (work.value().libraryEntries.length > 1) { <p class="muted">В библиотеке {{ work.value().libraryEntries.length }} записи этого произведения. <a class="text-link" routerLink="/library">Открыть записи и заметки</a></p> }
        @if (work.value().relatedWorks?.length) { <p class="muted">После уточнения совпадений доступны отдельные карточки.
          @for (id of work.value().relatedWorks; track id) { <a class="text-link" [routerLink]="['/catalog','works',id]">Открыть другой вариант</a> }
        </p> }
      </section>
      @if (selected(); as source) {
        @for (key of [source]; track key.titleId) { <app-title-page [sourceId]="key.sourceId" [externalTitleId]="key.externalTitleId"/> }
        <div class="wrap work-switch-note">Позиция чтения сохраняется отдельно в каждом источнике.
          @if (work.value().libraryEntries.length) { <a class="text-link" [routerLink]="['/library/transfer',work.value().libraryEntries[0].id]">Сопоставить главу с другим источником</a> }
        </div>
      } @else { <main class="wrap section-page"><app-state kind="empty" message="Подключите источник, чтобы открыть главы."><a class="button primary" routerLink="/browse">Открыть расширения</a></app-state></main> }
    }
  `,
})
export class WorkPage {
  readonly workId=input.required<string>();
  private readonly api=inject(Api);
  private readonly route=inject(ActivatedRoute);
  private readonly router=inject(Router);
  private readonly preview=inject(TitlePreviewState);
  protected readonly params=toSignal(this.route.queryParamMap,{requireSync:true});
  protected readonly problem=problem;
  protected readonly saving=signal(false);
  protected readonly issue=signal('');
  private readonly saved=signal<WorkDetail | undefined>(undefined);
  protected readonly work=rxResource({params:()=>({id:this.workId(),language:this.params().get('chapterLanguage') ?? ''}),
    stream:({params})=>this.api.work(params.id,params.language)});
  protected readonly selected=computed(() => {
    if(!this.work.hasValue())return undefined;
    const work=this.saved()?.workId===this.workId()?this.saved()!:this.work.value();
    const selected=this.params().get('source') || work.selectedTitleId;
    return work.sources?.find(source=>source.titleId===selected) ?? work.sources?.[0];
  });
  protected choose(titleId:string) {
    if(!this.work.hasValue() || this.saving())return;
    this.saving.set(true);this.issue.set('');
    const work=this.saved()?.workId===this.workId()?this.saved()!:this.work.value();
    const language=this.params().get('chapterLanguage');
    const source=work.sources?.find(item=>item.titleId===titleId);
    if(language && !source?.supportedLanguages.includes(language)) {
      this.saving.set(false);this.issue.set('У источника доступен язык '+source?.supportedLanguages.join(', ')+'. Сначала выберите его в поле «Язык глав».');return;
    }
    this.api.preferSource(this.workId(),titleId,this.params().get('chapterLanguage'),work.preference?.version ?? 0).subscribe({
      next:updated=>{this.saved.set(updated);this.saving.set(false);void this.router.navigate([],{queryParams:{source:titleId},queryParamsHandling:'merge',replaceUrl:true,state:{kadrTitleReturn:this.preview.pageReturn()}});},
      error:error=>{this.saving.set(false);this.issue.set(problem(error).detail);this.work.reload();this.saved.set(undefined);},
    });
  }
  protected supportsLanguage(source:WorkSource) {const language=this.params().get('chapterLanguage');return !language || source.supportedLanguages.includes(language);}
  protected setLanguage(language:string) {this.saved.set(undefined);this.issue.set('');void this.router.navigate([],{queryParams:{chapterLanguage:language || null},queryParamsHandling:'merge',replaceUrl:true,state:{kadrTitleReturn:this.preview.pageReturn()}});}
}
