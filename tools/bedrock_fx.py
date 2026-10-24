"""Generate Bedrock cinematic actors from vanilla client textures/models.

Usage: python tools/bedrock_fx.py path/to/client.jar [path/to/BanFX/resource-pack/pack]
The proxy streams the actual Java display poses; these assets apply left rotation,
nonuniform scale and right rotation in the same order, with smooth pose changes.
Generated files are checked in, so the normal CI pack build needs no client JAR.
"""
import io,json,math,shutil,sys,zipfile
from pathlib import Path
from PIL import Image,ImageFilter
ROOT=Path(__file__).resolve().parent.parent
PACK=ROOT/'bedrock/pack'
TYPES='mace label chain red_stained_glass_pane black_stained_glass_pane crimson_nylium redstone_block nether_wart_block netherrack red_nether_bricks lime_concrete gold_block iron_block black_concrete'.split()
PROPS='tx ty tz lx ly lz sx sy sz rx ry rz'.split()
def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
def model(z,name):
    name=name.removeprefix('minecraft:')
    d=json.loads(z.read('assets/minecraft/models/'+name+'.json'))
    parent=d.get('parent')
    if parent and not parent.startswith('builtin:'):
        try:base=model(z,parent)
        except KeyError:base={}
    else:base={}
    return {**base,**d,'textures':{**base.get('textures',{}),**d.get('textures',{})}}
def main():
    z=zipfile.ZipFile(sys.argv[1]);atlas=Image.new('RGBA',(128,128));tiles={}
    def texture(name):
        if name in tiles:return tiles[name]
        path=name.removeprefix('minecraft:')
        im=Image.open(io.BytesIO(z.read('assets/minecraft/textures/'+path+'.png'))).convert('RGBA').crop((0,0,16,16))
        if path.endswith('iron_chain'):
            a=im.getchannel('A');outline=Image.new('RGBA',im.size,(110,255,20,255));outline.putalpha(a.filter(ImageFilter.MaxFilter(3)));outline.alpha_composite(im);im=outline
        i=len(tiles);assert i<64
        at=(i%8*16,i//8*16);atlas.paste(im,at);tiles[name]=at;return at
    def resolve(textures,key):
        while key.startswith('#'):key=textures[key[1:]]
        return key
    def uv(tex,rect):
        x,y=texture(tex);u,v,U,V=rect
        return {'uv':[x+u,y+v],'uv_size':[U-u,V-v]}
    def bones():
        names=['origin','lz','ly','lx','size','rz','ry','rx','model']
        return [{'name':n,'pivot':[0,0,0],**({'parent':names[i-1]} if i else {})} for i,n in enumerate(names)]
    for kind in TYPES:
        b=bones()
        if kind=='mace':
            tex='minecraft:item/mace';im=Image.open(io.BytesIO(z.read('assets/minecraft/textures/item/mace.png'))).convert('RGBA');cubes=[]
            for y in range(16):
                for x in range(16):
                    if im.getpixel((x,y))[3]:cubes.append({'origin':[x-8,7-y,-.5],'size':[1,1,1],'uv':{face:uv(tex,[x,y,x+1,y+1]) for face in ['north','south','east','west','up','down']}})
            b[-1]['cubes']=cubes
            # Match ItemDisplay renderer's built-in 180-degree Y turn before its right rotation.
            b[-1]['rotation']=[0,180,0]
        elif kind!='label':
            name='block/'+('iron_chain' if kind=='chain' else kind)
            if kind.endswith('_pane'):
                tex='minecraft:block/'+kind.removesuffix('_pane')
                b[-1]['cubes']=[{'origin':[0,0,7],'size':[16,16,2],'uv':{face:uv(tex,[0,0,16,16]) for face in ['north','south','east','west','up','down']}}]
            else:
                m=model(z,name)
                for i,element in enumerate(m['elements']):
                    lo=element['from'];hi=element['to'];cube={'origin':lo,'size':[hi[j]-lo[j] for j in range(3)],'uv':{}}
                    for face,data in element['faces'].items():
                        cube['uv'][face]=uv(resolve(m['textures'],data['texture']),data.get('uv',[0,0,16,16]))
                    part={'name':'part_'+str(i),'parent':'model','pivot':[0,0,0],'cubes':[cube]}
                    if 'rotation' in element:
                        r=element['rotation'];part['pivot']=r['origin'];rot=[0,0,0];rot['xyz'.index(r['axis'])]=r['angle'];part['rotation']=rot
                    b.append(part)
        # Blockbench's Bedrock codec: mirror X for positions/cubes, flip X/Y rotations.
        # Keep the wire poses in Java coordinates; apply that same conversion in the rig.
        for bone in b:
            bone['pivot']=[-bone['pivot'][0],*bone['pivot'][1:]]
            if 'rotation' in bone:bone['rotation']=[-bone['rotation'][0],-bone['rotation'][1],bone['rotation'][2]]
            for cube in bone.get('cubes',[]):
                cube['origin']=[-cube['origin'][0]-cube['size'][0],*cube['origin'][1:]]
                for face in ['up','down']:
                    if face in cube['uv']:
                        f=cube['uv'][face];f['uv']=[f['uv'][j]+f['uv_size'][j] for j in range(2)];f['uv_size']=[-v for v in f['uv_size']]
        geometry={'format_version':'1.12.0','minecraft:geometry':[{'description':{'identifier':'geometry.taxsmp.fx_'+kind,'texture_width':128,'texture_height':128,'visible_bounds_width':32,'visible_bounds_height':32,'visible_bounds_offset':[0,6,0]},'bones':b}]}
        write(PACK/f'models/entity/taxsmp/fx_{kind}.geo.json',geometry)
        initialize=['v.serial=q.property(\'taxsmp:state\');','v.elapsed=0;']
        initialize += [f'v.c_{p}=q.property(\'taxsmp:{p}\');v.a_{p}=v.c_{p};' for p in PROPS]
        store=''.join(f'v.a_{p}=v.c_{p};' for p in PROPS)
        pre=['v.elapsed=v.elapsed+q.delta_time;',f"(v.serial!=q.property('taxsmp:state'))?{{v.serial=q.property('taxsmp:state');v.elapsed=0;{store}}};","v.f=q.property('taxsmp:duration')<=0?1:math.clamp(v.elapsed/q.property('taxsmp:duration'),0,1);"]
        pre += [f"v.c_{p}=math.{'lerprotate' if p[0] in 'lr' else 'lerp'}(v.a_{p},q.property('taxsmp:{p}'),v.f);" for p in PROPS]
        material='entity_emissive_alpha' if kind in ['mace','chain'] or kind.endswith('_pane') else 'entity_alphatest'
        write(PACK/f'entity/taxsmp/fx_{kind}.entity.json',{'format_version':'1.10.0','minecraft:client_entity':{'description':{'identifier':'taxsmp:fx_'+kind,'materials':{'default':material},'textures':{'default':'textures/entity/taxsmp/fx_atlas'},'geometry':{'default':'geometry.taxsmp.fx_'+kind},'animations':{'transform':'animation.taxsmp.fx_transform'},'scripts':{'initialize':initialize,'pre_animation':pre,'animate':['transform']},'render_controllers':['controller.render.taxsmp.fx']}}})
    atlas_path=PACK/'textures/entity/taxsmp/fx_atlas.png';atlas_path.parent.mkdir(parents=True,exist_ok=True);atlas.save(atlas_path)
    transforms={'origin':{'position':['-v.c_tx*16','v.c_ty*16','v.c_tz*16']},'size':{'scale':[f'v.c_s{axis}' for axis in 'xyz']}}
    for side in ['l','r']:
        for i,axis in enumerate('xyz'):
            vector=[0,0,0];vector[i]=('-' if axis!='z' else '')+f'v.c_{side}{axis}';transforms[side+axis]={'rotation':vector}
    write(PACK/'animations/taxsmp/fx.animation.json',{'format_version':'1.8.0','animations':{'animation.taxsmp.fx_transform':{'loop':True,'bones':transforms}}})
    write(PACK/'render_controllers/taxsmp/fx.render_controllers.json',{'format_version':'1.8.0','render_controllers':{'controller.render.taxsmp.fx':{'geometry':'Geometry.default','materials':[{'*':'Material.default'}],'textures':['Texture.default']}}})
    if len(sys.argv)>2:
        audio=Path(sys.argv[2])/'assets/taxsmp/sounds/ban'
        for name in ['charge','impact']:
            for target in [PACK/'sounds/taxsmp/ban'/f'{name}.ogg',ROOT/'pack/assets/taxsmp/sounds/ban'/f'{name}.ogg']:
                target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(audio/f'{name}.ogg',target)
        write(PACK/'sounds/sound_definitions.json',{'format_version':'1.14.0','sound_definitions':{f'taxsmp:ban.{name}':{'category':'neutral','sounds':[{'name':f'sounds/taxsmp/ban/{name}','is3D':True}]} for name in ['charge','impact']}})
        java=ROOT/'pack/assets/taxsmp/sounds.json';defs=json.loads(java.read_text()) if java.exists() else {};defs.update({f'ban.{name}':{'sounds':[f'taxsmp:ban/{name}']} for name in ['charge','impact']});write(java,defs)
    print(f'Built {len(TYPES)} Bedrock cinematic actors with shared smooth transform rig and {len(tiles)} vanilla texture tiles')
if __name__=='__main__':main()
