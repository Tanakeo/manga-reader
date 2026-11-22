import { Component, computed, DestroyRef, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap } from 'rxjs';
import { TranslocoDirective } from '@jsverse/transloco';
import { Api, ChapterAvailability, languageName, problem, TitleSummary } from './api';

@Component({
  selector: 'app-catalog-facts',
  imports: [TranslocoDirective],
  template: `
    <ng-container *transloco="let t; prefix: 'catalogFacts'">
      @if (item().publicationStatus || item().releaseYear) {
        <span class="catalog-facts-tags">
          @if (item().publicationStatus; as status) {
            <span class="status-tag" [class.status-ongoing]="status === 'ongoing'">{{ t('status.' + status) }}</span>
          }
          @if (item().releaseYear; as year) { <span>{{ year }}</span> }
        </span>
      }
      @let check = checkState();
      @if (check?.state === 'COMPLETE' && check?.chapterCount !== null) {
        <span>{{ t('chapters', {count: check?.chapterCount}) }}</span>
      } @else if (check?.state === 'ERROR') {
        <span>Проверка глав временно недоступна</span>
      } @else if (check?.state === 'PARTIAL') {
        <span>Найдено глав: {{ check?.knownChapterCount }} · проверка продолжается</span>
      } @else {
        <span>{{ t('countPending') }}</span>
      }
      @if (check?.languages?.length) {
        <span class="availability-languages">Языки проверки: {{ languageLabel(check!.languages) }}</span>
      }
      @if (check?.state === 'ERROR' || check?.state === 'PARTIAL' || check?.state === 'UNVERIFIED' || check?.state === 'CHECKING') {
        @if (check?.error?.detail) { <span class="availability-error">{{ check?.error?.detail }}</span> }
        @if (interactive()) {
          <button class="button secondary compact" type="button" [disabled]="busy()"
            (click)="retry($event)">{{ busy() ? 'Проверяем…' : 'Проверить главы' }}</button>
        }
      }
      @if (issue()) { <span class="availability-error" role="alert">{{ issue() }}</span> }
    </ng-container>
  `,
})
export class CatalogFacts {
  readonly item = input.required<TitleSummary>();
  readonly availability = input<ChapterAvailability>();
  readonly language = input('');
  readonly interactive = input(true);
  readonly changed = output<void>();
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  protected readonly busy = signal(false);
  protected readonly issue = signal('');
  protected readonly checkState = computed(() => this.api.chapterAvailability(this.item(), this.language())
    ?? this.availability() ?? this.item().chapterAvailability);
  protected languageLabel(languages: string[]) { return languages.map(languageName).join(', '); }
  protected retry(event: Event) {
    event.preventDefault(); event.stopPropagation();
    if (this.busy()) return;
    this.busy.set(true); this.issue.set('');
    this.api.checkChapters(this.item()).pipe(switchMap(() => this.api.chapters(this.item(), this.language())),
      takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      error: error => this.issue.set(problem(error).detail),
      complete: () => this.changed.emit(),
    });
  }
}
