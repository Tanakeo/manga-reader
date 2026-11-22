import { Component, DestroyRef, inject, input, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, switchMap, timer } from 'rxjs';
import { Api, problem, SyncRun } from './api';

@Component({
  selector: 'app-sync-progress',
  host: {style: 'display:block;width:100%;margin-top:12px'},
  templateUrl: './sync-progress.html',
})
export class SyncProgress {
  private readonly api = inject(Api);
  private readonly destroy = inject(DestroyRef);
  readonly source = input.required<string>();
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly state = rxResource({
    params: this.source,
    stream: ({params}) => timer(0, 5000).pipe(switchMap(() => this.api.syncStatus(params))),
  });

  protected start() { this.act(this.api.startSync(this.source())); }
  protected change(run: SyncRun) {
    this.act(this.api.changeSync(this.source(), run.runId, run.state === 'PAUSED' ? 'resume' : 'pause'));
  }
  private act(request: ReturnType<Api['startSync']>) {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    request.pipe(takeUntilDestroyed(this.destroy), finalize(() => this.busy.set(false))).subscribe({
      next: () => this.state.reload(),
      error: error => this.error.set(problem(error).detail),
    });
  }
}
