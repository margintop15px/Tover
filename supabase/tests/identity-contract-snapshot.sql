-- Stable identity schema/permission fingerprint, without account data or secrets.
SELECT jsonb_build_object(
  'columns',(SELECT jsonb_agg(jsonb_build_array(c.relname,a.attname,format_type(a.atttypid,a.atttypmod),a.attnotnull,
    pg_get_expr(d.adbin,d.adrelid)) ORDER BY c.relname,a.attnum)
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
    LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
    WHERE n.nspname='public' AND c.relname IN ('profiles','roles','organizations','organization_memberships','organization_invites')
      AND a.attnum>0 AND NOT a.attisdropped),
  'constraints',(SELECT jsonb_agg(jsonb_build_array(c.relname,k.conname,pg_get_constraintdef(k.oid)) ORDER BY c.relname,k.conname)
    FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relname IN ('profiles','roles','organizations','organization_memberships','organization_invites')),
  'policies',(SELECT jsonb_agg(to_jsonb(p) ORDER BY p.tablename,p.policyname) FROM pg_policies p
    WHERE p.schemaname='public' AND p.tablename IN ('profiles','roles','organizations','organization_memberships','organization_invites')),
  'grants',(SELECT jsonb_agg(jsonb_build_array(grantee,table_name,privilege_type) ORDER BY grantee,table_name,privilege_type)
    FROM information_schema.table_privileges WHERE table_schema='public'
      AND table_name IN ('profiles','roles','organizations','organization_memberships','organization_invites')),
  'functions',(SELECT jsonb_agg(jsonb_build_array(n.nspname,p.proname,pg_get_functiondef(p.oid),p.proacl::text)
    ORDER BY n.nspname,p.proname) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='private' OR (n.nspname='public' AND p.proname IN ('create_organization','my_workspaces',
      'workspace_team','manage_workspace_invitation','request_member_recovery','accept_my_organization_invites',
      'revoke_workspace_invitation_reservation','bootstrap_new_user','app_is_org_member','app_has_org_role','set_updated_at')))
)::text;
