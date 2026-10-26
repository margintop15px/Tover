(ns tover.workspaces.core)


(def Membership
  [:map
   [:workspace-id :uuid]
   [:name :string]
   [:role [:enum :owner :admin :member]]
   [:created-at :string]])


(defprotocol Directory
  "Workspace authorization uses the caller's verified access token."
  (^{:malli/schema [:=> [:cat :any :string] [:vector :map]]}
    list-memberships [provider access-token])
  (^{:malli/schema [:=> [:cat :any :string :string] :uuid]}
    create-workspace! [provider access-token name])
  (^{:malli/schema [:=> [:cat :any :string :uuid] :map]}
    team [provider access-token workspace-id])
  (^{:malli/schema [:=> [:cat :any :string :uuid [:vector :uuid]] [:map-of :string :string]]}
    actor-labels [provider access-token workspace-id actor-ids])
  (^{:malli/schema [:=> [:cat :any :string :map] :map]}
    manage-invitation! [provider access-token input])
  (^{:malli/schema [:=> [:cat :any :string :uuid :uuid] :nil]}
    revoke-reservation! [provider access-token workspace-id invite-id])
  (^{:malli/schema [:=> [:cat :any :string :uuid :uuid] :map]}
    request-member-recovery! [provider access-token workspace-id user-id])
  (^{:malli/schema [:=> [:cat :any :string] :int]}
    accept-invites! [provider access-token]))
