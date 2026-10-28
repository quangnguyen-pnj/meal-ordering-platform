import { HttpStatus, Injectable, type PipeTransform } from '@nestjs/common';
import type { ZodTypeAny, z } from 'zod';
import { ApiException } from './api-error';

/** Validates a request body or query with a shared Zod schema (packages/shared). */
@Injectable()
export class ZodValidationPipe<S extends ZodTypeAny> implements PipeTransform<unknown, z.infer<S>> {
  constructor(private readonly schema: S) {}

  transform(value: unknown): z.infer<S> {
    const result = this.schema.safeParse(value);
    if (!result.success) {
      throw new ApiException(
        HttpStatus.BAD_REQUEST,
        'VALIDATION_FAILED',
        'Request validation failed',
        {
          details: result.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })),
        },
      );
    }
    return result.data;
  }
}
