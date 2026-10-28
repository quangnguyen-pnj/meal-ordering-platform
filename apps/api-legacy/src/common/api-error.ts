import {
  ArgumentsHost,
  Catch,
  HttpException,
  HttpStatus,
  Logger,
  type ExceptionFilter,
} from '@nestjs/common';
import type { ApiErrorBody, ApiErrorCode } from '@meal/shared';
import type { Response } from 'express';

/** HttpException carrying a machine-readable code; the web maps codes to Vietnamese copy. */
export class ApiException extends HttpException {
  constructor(
    status: HttpStatus,
    code: ApiErrorCode,
    message: string,
    extra: Partial<Pick<ApiErrorBody, 'details' | 'lockedUntil'>> = {},
  ) {
    super({ statusCode: status, code, message, ...extra }, status);
  }
}

const CODE_BY_STATUS: Record<number, ApiErrorCode> = {
  [HttpStatus.BAD_REQUEST]: 'VALIDATION_FAILED',
  [HttpStatus.UNAUTHORIZED]: 'UNAUTHENTICATED',
  [HttpStatus.FORBIDDEN]: 'FORBIDDEN',
  [HttpStatus.NOT_FOUND]: 'NOT_FOUND',
};

@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  private readonly logger = new Logger(ApiExceptionFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    const res = host.switchToHttp().getResponse<Response>();

    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      const raw = exception.getResponse();
      if (typeof raw === 'object' && raw !== null && 'code' in raw) {
        res.status(status).json(raw);
        return;
      }
      const message = typeof raw === 'string' ? raw : exception.message;
      const body: ApiErrorBody = {
        statusCode: status,
        code: CODE_BY_STATUS[status] ?? 'INTERNAL',
        message,
      };
      res.status(status).json(body);
      return;
    }

    this.logger.error(exception instanceof Error ? exception.stack : String(exception));
    const body: ApiErrorBody = {
      statusCode: 500,
      code: 'INTERNAL',
      message: 'Internal server error',
    };
    res.status(500).json(body);
  }
}

export const notFound = (what = 'Not found') =>
  new ApiException(HttpStatus.NOT_FOUND, 'NOT_FOUND', what);
export const invalid = (message: string, details?: unknown) =>
  new ApiException(HttpStatus.BAD_REQUEST, 'VALIDATION_FAILED', message, { details });
export const conflict = (message: string, details?: unknown) =>
  new ApiException(HttpStatus.CONFLICT, 'CONFLICT', message, { details });
export const invalidState = (message: string, details?: unknown) =>
  new ApiException(HttpStatus.CONFLICT, 'INVALID_STATE', message, { details });
