import { afterNextRender, Component, DestroyRef, ElementRef, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { TranslocoDirective } from '@jsverse/transloco';
import { Api, TitleSummary } from './api';

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
      @if (count.hasValue()) {
        <span>{{ t('chapters', {count: count.value()}) }}</span>
      } @else if (count.error()) {
        <span>{{ t('countError') }}</span>
      } @else {
        <span>{{ t('countPending') }}</span>
      }
    </ng-container>
  `,
})
export class CatalogFacts {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly destroy = inject(DestroyRef);
  private readonly inView = signal(false);
  readonly item = input.required<TitleSummary>();
  protected readonly count = rxResource({
    params: () => this.inView() ? this.item() : undefined,
    stream: ({params}) => this.api.chapterCount(params),
  });

  constructor() {
    afterNextRender(() => {
      if (typeof IntersectionObserver === 'undefined') {
        this.inView.set(true);
        return;
      }
      const observer = new IntersectionObserver(entries => {
        if (!entries.some(entry => entry.isIntersecting)) return;
        this.inView.set(true);
        observer.disconnect();
      }, {rootMargin: '120px'});
      observer.observe(this.host.nativeElement);
      this.destroy.onDestroy(() => observer.disconnect());
    });
  }
}
