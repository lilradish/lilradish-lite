import { useMemo, useState } from "react";

import { readMeasurements, type Measured } from "../../api/measurements";
import type { SurfaceAct } from "../../api/standing";
import { Async } from "../../app/Async";
import { say, type MessageId } from "../../i18n/app";
import type { Ordering } from "../../lib/collection/ordering";
import { WholeTable } from "../../lib/collection/SortableTable";
import { PageHeading } from "../../lib/heading/PageHeading";
import { Part } from "../../lib/layout/Part";
import { useResource } from "../../lib/request/useResource";
import {
  BY_MODEL,
  inOrder,
  modelAndMode,
  modelColumns,
  modelOf,
  systemColumns,
  type ModelColumn,
  type SystemColumn,
} from "./measurementColumns";

export { MEASUREMENTS_PAGE } from "../../app/addresses";

const NOTHING_MEASURED: Measured = { models: [], system: [] };

/**
 * What every model has done, and apart from it where calls to each failed,
 * counted over everything ever recorded. Read whole and sorted here; each
 * table keeps its own order, and nothing on the page acts.
 */
export function MeasurementsPage({
  title,
  act,
}: {
  readonly title: MessageId;
  readonly act: SurfaceAct;
}) {
  const read = useResource(readMeasurements, NOTHING_MEASURED);
  const [modelOrder, setModelOrder] = useState<Ordering<ModelColumn>>(BY_MODEL);
  const [systemOrder, setSystemOrder] =
    useState<Ordering<SystemColumn>>(BY_MODEL);
  const models = useMemo(() => modelColumns(), []);
  const system = useMemo(() => systemColumns(), []);

  return (
    <>
      <PageHeading title={say(title)} />
      <Async read={read} rule={{ act }} empty={() => null}>
        {(measured) => (
          <>
            <Part title={say("measurements.models")} level="h2" depth={1}>
              <WholeTable
                label={say("measurements.models")}
                columns={models}
                order={modelOrder}
                onOrder={setModelOrder}
                rows={inOrder(measured.models, modelOrder)}
                keyOf={modelAndMode}
                empty={say("measurements.nothingCalled")}
              />
            </Part>
            <Part title={say("measurements.system")} level="h2" depth={1}>
              <WholeTable
                label={say("measurements.system")}
                columns={system}
                order={systemOrder}
                onOrder={setSystemOrder}
                rows={inOrder(measured.system, systemOrder)}
                keyOf={modelOf}
                empty={say("measurements.nothingCalled")}
              />
            </Part>
          </>
        )}
      </Async>
    </>
  );
}
