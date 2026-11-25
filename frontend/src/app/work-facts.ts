import { Component, computed, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { TitleSummary } from './api';

@Component({
  selector: 'app-work-facts',
  imports: [DatePipe],
  template: `
    @if (metric(); as metric) {
      <p class="work-metric" [title]="explanation()">
        @if (metric.metric === 'FOLLOWERS') { {{ format(metric.value,0) }} подписчиков }
        @else { {{ format(metric.value,2) }} / 10 <span class="muted">· {{ format(metric.sampleSize,0) }} оценок</span> }
      </p>
      <small class="muted">MangaDex · {{ metric.fetchedAt | date:'dd.MM.yyyy' }}@if (metric.stale) { · Устаревшие данные }</small>
    }
    <div class="work-sources" aria-label="Источники произведения">
      @for (source of item().sources ?? []; track source.titleId) {
        <span class="status-tag" [title]="source.chapterAvailability?.presence === 'FOUND' ? 'Есть доступные главы' : 'Доступность глав уточняется'">
          {{ source.sourceName }}
          @if (source.chapterAvailability?.presence !== 'FOUND') { <span aria-label="Проверка глав ожидается"> · ?</span> }
        </span>
      }
    </div>
  `,
})
export class WorkFacts {
  readonly item = input.required<TitleSummary>();
  readonly kind = input('');
  protected readonly metric = computed(() => this.kind() === 'popular'
    ? this.item().metrics?.find(m => m.metric === 'FOLLOWERS' && m.value !== null)
    : this.item().metrics?.find(m => m.metric === 'RATING_BAYESIAN' && m.value !== null));
  protected explanation() {
    return this.kind() === 'popular' ? 'Число подписчиков произведения на MangaDex'
      : 'Байесовская оценка MangaDex с учётом распределения голосов. В подборке от 100 оценок.';
  }
  protected format(value:number | null,digits:number) {
    return value===null?'—':new Intl.NumberFormat('ru-RU',{minimumFractionDigits:digits,maximumFractionDigits:digits}).format(value);
  }
}
