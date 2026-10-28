// embedded-postgres ships ESM-only types that the CommonJS resolution used here cannot see.
// This minimal declaration covers the subset scripts/local-postgres.ts uses.
declare module 'embedded-postgres' {
  interface EmbeddedPostgresOptions {
    databaseDir: string;
    user: string;
    password: string;
    port: number;
    persistent: boolean;
    initdbFlags?: string[];
    onLog?: (message: unknown) => void;
    onError?: (error: unknown) => void;
  }
  export default class EmbeddedPostgres {
    constructor(options: EmbeddedPostgresOptions);
    initialise(): Promise<void>;
    start(): Promise<void>;
    stop(): Promise<void>;
    createDatabase(name: string): Promise<void>;
  }
}
