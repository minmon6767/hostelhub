# Problem Statement

## The problem

Hostel administration in most colleges is still handled on paper. Two parts of it cause
the most friction:

**Room allocation.** Rooms are handed out by whoever is at the desk when a student walks
in. There is no consistent rule, so occupancy spreads thinly — a block can be running at
40% with residents scattered over three floors while every corridor is lit, cleaned and
staffed. Nobody can answer "how many beds are free in B block?" without walking the
building. Worse, when a student changes rooms mid-term, the old entry is simply
overwritten, so the hostel has no record of who stayed where.

**Maintenance complaints.** A student reports a broken fan verbally or on a slip of
paper. It reaches whoever is available, not whoever is qualified. There is no record of
when it was raised, so nothing is ever formally late, and no one can say which problems
recur or how long repairs actually take.

Both are ordinary record-keeping problems, and both are made worse by the absence of any
system that enforces its own rules.

## Proposed solution

HostelHub is a console application that puts these two workflows into one place and,
importantly, makes the rules explicit rather than leaving them to whoever is on duty:

- Every student record is validated before it is accepted
- Room allocation runs through a named, switchable strategy, so the warden chooses a
  policy once instead of making a fresh judgement per student
- Vacating a room closes the allocation record rather than deleting it, preserving
  history
- Every complaint gets an ID, a timestamp, a priority with an attached SLA and a fixed
  lifecycle it cannot skip steps in
- Staff can only be given work matching their trade, and the system suggests whoever is
  least loaded
- Reports answer the occupancy and complaint questions that currently require a walk
  around the building

## Scope

**In scope**

- Student registry: register, search, list, update contact details, remove
- Room inventory across blocks, floors and three room types, with a maintenance flag
- Bed-level allocation with three interchangeable strategies plus a manual override
- Allocation history that survives vacating and re-allocation
- Complaint logging, trade-based assignment, lifecycle enforcement, SLA tracking
- Occupancy, vacancy, complaint and resident reports
- Local persistence to CSV files, with a log file for every significant action

**Out of scope for this version**

- Fee collection and dues tracking (room rent is defined but payments are not recorded)
- Authentication and role separation — anyone at the terminal has full access
- Visitor logs, mess management, attendance
- Multi-user or networked access; the CSV store assumes one process at a time
- Any graphical or web interface

## Target users

**Hostel warden / office clerk** — the primary user. Registers students, allocates and
vacates rooms, blocks rooms for repair, assigns complaints and reads the reports.

**Maintenance supervisor** — a secondary user who looks at the complaint queue, the
overdue list and staff workload to plan the day's repairs.

**Hostel administration / management** — reads the occupancy and complaint reports to
decide on capacity and on whether repairs are being closed in reasonable time.

Students are represented in the system but are not direct users in this version; they
report problems at the desk, and the clerk logs them.

## High-level features

1. **Student Registry** — validated CRUD over resident records, with live room status
2. **Room & Allocation Engine** — strategy-driven bed allocation, manual override,
   vacating with history, maintenance blocking
3. **Complaint Desk** — categorised complaints, SLA per priority, enforced lifecycle,
   trade-matched assignment with workload balancing, overdue tracking
4. **Reports & Analytics** — occupancy by block, vacancy listing, complaint breakdown
   with average resolution time, resident digest by branch
5. **Persistence & Logging** — CSV-backed generic repository, append-only action log,
   first-run seeding so the system is usable immediately
