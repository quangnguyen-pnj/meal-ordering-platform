import { z } from 'zod';

const NullableId = z.string().min(1).nullable();

/** BR-60 / BR-62: meal-unit flag and the node's own floor, canteen and cost center (null = inherit). */
export const OrgUnitUpdateSchema = z
  .object({
    isMealUnit: z.boolean(),
    floorId: NullableId,
    canteenId: NullableId,
    costCenterId: NullableId,
  })
  .partial()
  .refine((v) => Object.keys(v).length > 0, 'Nothing to update');
export type OrgUnitUpdate = z.infer<typeof OrgUnitUpdateSchema>;
