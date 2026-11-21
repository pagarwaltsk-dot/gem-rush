-- Gem Rush weekly leaderboard
-- Paste this whole script into Supabase: SQL Editor > New query > Run. Running it again is safe.

create table if not exists public.gr_scores (
  week_start date not null,
  pid text not null,
  name text not null,
  state text,
  points integer not null default 0,
  games integer not null default 0,
  updated_at timestamptz not null default now(),
  primary key (week_start, pid)
);
alter table public.gr_scores enable row level security;
-- No direct access to the table: players can only add scores and read the board through the functions below.
revoke all on public.gr_scores from anon, authenticated;

-- The week starts on Monday, Indian time
create or replace function public.gr_week() returns date
language sql stable as $$ select (date_trunc('week', now() at time zone 'Asia/Kolkata'))::date $$;

-- Add one finished game to this week's total.
-- Only the game's fun names are accepted (never real names), the state must be from the list,
-- points per game are capped, and one phone can add at most one game every 20 seconds.
create or replace function public.gr_submit(p_pid text, p_name text, p_state text, p_score integer)
returns void language plpgsql security definer set search_path = public as $$
declare
  adj text[] := array['Swift','Golden','Brave','Clever','Mighty','Lucky','Happy','Shiny','Jolly','Speedy','Bright','Cosmic','Fearless','Sunny','Wise','Zippy','Royal','Bold','Calm','Daring'];
  ani text[] := array['Rhino','Tiger','Owl','Panda','Dolphin','Falcon','Elephant','Peacock','Leopard','Otter','Koala','Eagle','Fox','Turtle','Gibbon','Rabbit','Yak','Parrot','Panther','Squirrel'];
  st  text[] := array['Andaman and Nicobar','Andhra Pradesh','Arunachal Pradesh','Assam','Bihar','Chandigarh','Chhattisgarh','Dadra Nagar Haveli and Daman Diu','Delhi','Goa','Gujarat','Haryana','Himachal Pradesh','Jammu and Kashmir','Jharkhand','Karnataka','Kerala','Ladakh','Lakshadweep','Madhya Pradesh','Maharashtra','Manipur','Meghalaya','Mizoram','Nagaland','Odisha','Puducherry','Punjab','Rajasthan','Sikkim','Tamil Nadu','Telangana','Tripura','Uttar Pradesh','Uttarakhand','West Bengal'];
  parts text[];
begin
  if p_pid is null or p_pid !~ '^[A-Za-z0-9]{4,24}$' then return; end if;
  parts := regexp_split_to_array(coalesce(p_name, ''), ' ');
  if coalesce(array_length(parts, 1), 0) <> 3 or not (parts[1] = any(adj)) or not (parts[2] = any(ani))
     or parts[3] !~ '^[1-9][0-9]?$' then return; end if;
  if p_state is not null and not (p_state = any(st)) then p_state := null; end if;
  insert into public.gr_scores as s (week_start, pid, name, state, points, games)
  values (public.gr_week(), p_pid, p_name, p_state, greatest(0, least(coalesce(p_score, 0), 120)) + 2, 1)
  on conflict (week_start, pid) do update
    set name = excluded.name, state = excluded.state,
        points = s.points + excluded.points, games = s.games + 1, updated_at = now()
    where s.games < 300 and s.updated_at < now() - interval '20 seconds';
end $$;

-- Top 50 players this week, plus the asking phone's own rank
create or replace function public.gr_board(p_pid text)
returns json language sql stable security definer set search_path = public as $$
  with r as (
    select pid, name, state, points, games,
           rank() over (order by points desc, updated_at asc) as rk
    from public.gr_scores where week_start = public.gr_week()
  )
  select json_build_object(
    'top', coalesce((select json_agg(json_build_object('name', name, 'state', state, 'points', points, 'games', games) order by rk)
                     from (select * from r order by rk limit 50) t), '[]'::json),
    'me',  (select json_build_object('rank', rk, 'points', points) from r where pid = p_pid)
  )
$$;

-- State vs State: this week's points per state
create or replace function public.gr_states()
returns json language sql stable security definer set search_path = public as $$
  select coalesce(json_agg(x order by x.points desc), '[]'::json) from (
    select state, sum(points)::int as points, count(*)::int as players
    from public.gr_scores
    where week_start = public.gr_week() and state is not null
    group by state
  ) x
$$;

revoke all on function public.gr_submit(text, text, text, integer) from public;
revoke all on function public.gr_board(text) from public;
revoke all on function public.gr_states() from public;
grant execute on function public.gr_submit(text, text, text, integer) to anon, authenticated;
grant execute on function public.gr_board(text) to anon, authenticated;
grant execute on function public.gr_states() to anon, authenticated;
