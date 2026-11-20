import { DestroyRef, Directive, ElementRef, inject, input } from '@angular/core';
import { TitleSummary } from '../api';
import { previewKey, TitlePreviewState } from './title-preview-state';

@Directive({
  selector: 'a[titlePreview]',
  host: {'aria-haspopup': 'dialog', '[attr.data-preview-key]': 'key()'},
})
export class TitlePreviewLink {
  readonly titlePreview = input.required<TitleSummary>();
  private readonly preview = inject(TitlePreviewState);
  private readonly anchor = inject<ElementRef<HTMLAnchorElement>>(ElementRef).nativeElement;
  protected key() { return previewKey(this.titlePreview()); }

  constructor() {
    // Capture runs before RouterLink's bubbling handler. Modified clicks remain native.
    const open = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey ||
          this.anchor.target === '_blank' || this.anchor.hasAttribute('download')) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      this.preview.open(this.titlePreview(), this.anchor);
    };
    this.anchor.addEventListener('click', open, true);
    inject(DestroyRef).onDestroy(() => this.anchor.removeEventListener('click', open, true));
  }
}
