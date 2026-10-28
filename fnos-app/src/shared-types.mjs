// Product composition root; adding a type does not change protocol or Writer code.
import {courseColorAdapter} from './shared-course-colors.mjs';
import {courseScheduleAdapter} from './shared-course-schedule.mjs';
export const sharedTypes=[courseColorAdapter,courseScheduleAdapter];
